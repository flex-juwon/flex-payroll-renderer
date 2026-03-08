package team.flex.payroll

import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page.PdfOptions
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.options.LoadState
import org.apache.commons.pool2.BasePooledObjectFactory
import org.apache.commons.pool2.ObjectPool
import org.apache.commons.pool2.PooledObject
import org.apache.commons.pool2.PooledObjectFactory
import org.apache.commons.pool2.impl.DefaultPooledObject
import org.apache.commons.pool2.impl.GenericObjectPool
import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.web.ServerProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.time.Duration

object PayrollRendererConfig {
    private const val BROWSER_POOL_MIN_SIZE = 2
    private const val BROWSER_POOL_MAX_SIZE = 8
    const val BROWSER_WAIT_TIMEOUT_MILLIS = 5000L

    // Fix Issue 1: coerceAtMost/coerceAtLeast 순서가 반전되어 있었음 → 항상 MAX=8을 반환하는 버그 수정
    val BROWSER_POOL_SIZE = Runtime.getRuntime().availableProcessors()
        .coerceAtLeast(BROWSER_POOL_MIN_SIZE)  // 최소 2
        .coerceAtMost(BROWSER_POOL_MAX_SIZE)   // 최대 8

    const val RETRY_INITIAL_DELAY_MILLIS = 1000L
    const val RETRY_MAX_ATTEMPTS = 3
    const val RETRY_BACKOFF_MULTIPLIER = 2
}

@SpringBootApplication
class PayrollRendererApplication {
    // Fix Issue 4: 공유 Playwright 싱글톤 제거 — 각 풀 슬롯이 독립적인 Playwright 인스턴스를 소유
    @Bean(destroyMethod = "close")
    fun browserPool(): BrowserPool = BrowserPool()
}

fun main(args: Array<String>) {
    runApplication<PayrollRendererApplication>(*args)
}

@RestController
class HelloController(
    private val pdfRenderer: PdfRenderer,
) {
    @PostMapping(
        value = ["/api/hello"],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_PDF_VALUE],
    )
    fun hello(@RequestBody command: HelloCommand): ResponseEntity<InputStreamResource> {
        val pdfBytes = pdfRenderer.renderHtmlToPdf(command)

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=hello.pdf")
            .body(InputStreamResource(ByteArrayInputStream(pdfBytes)))
    }
}

data class HelloCommand(val name: String)

@Component
class PdfRenderer(
    private val browserPool: BrowserPool,
    private val serverProperties: ServerProperties,
    private val objectMapper: ObjectMapper,
) {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    // Fix Issue 2: 전역 Mutex 제거 — BrowserPool이 동시성을 제어하므로 별도의 락 불필요
    // BrowserPool의 maxTotal이 최대 동시 렌더링 수를 결정하며, 풀 소진 시 maxWait 후 타임아웃
    fun renderHtmlToPdf(command: HelloCommand): ByteArray {
        val maxAttempts = PayrollRendererConfig.RETRY_MAX_ATTEMPTS
        var attempt = 0
        var delayMillis = PayrollRendererConfig.RETRY_INITIAL_DELAY_MILLIS

        while (true) {
            try {
                return doRender(command)
            } catch (e: PlaywrightException) {
                if (++attempt >= maxAttempts) throw e
                logger.warn("pdf 렌더링 작업을 {}ms 후에 재시도합니다: attempt={}/{}", delayMillis, attempt, maxAttempts)
                Thread.sleep(delayMillis)
                delayMillis *= PayrollRendererConfig.RETRY_BACKOFF_MULTIPLIER
            }
        }
    }

    private fun doRender(command: HelloCommand): ByteArray {
        val data = objectMapper.writeValueAsString(command)
        val entry = browserPool.borrowObject()

        // Fix Issue 3: 예외 발생 시 브라우저 누수 방지
        // - 정상 종료: returnObject로 풀에 반납 (also 블록)
        // - 예외 발생: invalidateObject로 비정상 브라우저 폐기 후 재생성 (catch 블록)
        return try {
            entry.browser.newContext().use { context ->
                context.newPage().use { page ->
                    page.addInitScript("window.initialData = $data")
                    page.navigate("http://localhost:${serverProperties.port}")
                    page.waitForLoadState(LoadState.NETWORKIDLE)

                    logger.info(
                        "html 페이지를 렌더링했습니다. pdf 렌더링을 시작합니다: browserId={}, contextId={}, pageId={}, data={}",
                        entry.browser.hashCode(), context.hashCode(), page.hashCode(), data
                    )

                    page.pdf(PdfOptions().setFormat("A4").setPrintBackground(true))
                }
            }
        } catch (e: Exception) {
            browserPool.invalidateObject(entry)
            throw e
        }.also {
            browserPool.returnObject(entry)
        }
    }
}

// Fix Issue 4: Playwright 멀티스레드 안전성 — 풀 슬롯별로 독립적인 Playwright 인스턴스 보유
// Playwright 인스턴스는 스레드에 귀속되어야 하므로 단일 인스턴스를 여러 스레드에서 공유하면 안 됨
data class BrowserWithPlaywright(val playwright: Playwright, val browser: Browser) : Closeable {
    override fun close() {
        browser.close()
        playwright.close()
    }
}

class BrowserPool : Closeable {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    private val poolSize = PayrollRendererConfig.BROWSER_POOL_SIZE
    private val pool: ObjectPool<BrowserWithPlaywright>

    init {
        val factory: PooledObjectFactory<BrowserWithPlaywright> =
            object : BasePooledObjectFactory<BrowserWithPlaywright>() {
                private val logger: Logger = LoggerFactory.getLogger(javaClass)

                override fun create(): BrowserWithPlaywright {
                    // 각 풀 슬롯마다 독립적인 Playwright + Browser 인스턴스 생성
                    val pw = Playwright.create()
                    val browser = pw.chromium().launch(
                        // see https://playwright.dev/java/docs/intro
                        // see https://peter.sh/experiments/chromium-command-line-switches/
                        BrowserType.LaunchOptions()
                            .setChannel("chromium")
                            .setHeadless(true)
                            .setArgs(listOf("--disable-gpu"))
                    )

                    logger.info("새 브라우저 인스턴스를 생성했습니다: browserId={}", browser.hashCode())

                    return BrowserWithPlaywright(pw, browser)
                }

                override fun wrap(entry: BrowserWithPlaywright?): PooledObject<BrowserWithPlaywright> =
                    DefaultPooledObject(entry)

                // Fix Issue 5: validateObject 경량화 — 새 페이지 생성 대신 isConnected() 사용
                override fun validateObject(pooledObject: PooledObject<BrowserWithPlaywright>): Boolean =
                    pooledObject.`object`.browser.isConnected

                override fun destroyObject(p: PooledObject<BrowserWithPlaywright>) {
                    p.`object`.close()
                }
            }

        // see org.apache.commons.pool2.impl.GenericObjectPoolConfig
        val config: GenericObjectPoolConfig<BrowserWithPlaywright> =
            GenericObjectPoolConfig<BrowserWithPlaywright>().apply {
                minIdle = poolSize
                maxIdle = poolSize
                maxTotal = poolSize
                testOnCreate = true
                testOnBorrow = true
                testWhileIdle = true
                testOnReturn = false  // 반납 시 검증 생략으로 오버헤드 완화
                setMaxWait(Duration.ofMillis(PayrollRendererConfig.BROWSER_WAIT_TIMEOUT_MILLIS))
            }

        pool = GenericObjectPool(factory, config).apply {
            addObjects(poolSize)
        }

        logger.info(
            "브라우저 풀이 준비됐습니다: idle={}, active={}, waiting={}",
            pool.numIdle, pool.numActive, pool.numWaiters
        )
    }

    fun borrowObject(): BrowserWithPlaywright {
        val entry = pool.borrowObject()

        return when (pool) {
            is GenericObjectPool -> {
                logger.info(
                    "브라우저 인스턴스를 구했습니다: browserId={}, idle={}, active={}, waiting={}",
                    entry.browser.hashCode(), pool.numIdle, pool.numActive, pool.numWaiters
                )
                entry
            }

            else -> entry
        }
    }

    fun returnObject(entry: BrowserWithPlaywright) {
        pool.returnObject(entry)

        when (pool) {
            is GenericObjectPool -> {
                logger.info(
                    "브라우저 인스턴스를 반납했습니다: browserId={}, idle={}, active={}, waiting={}",
                    entry.browser.hashCode(), pool.numIdle, pool.numActive, pool.numWaiters,
                )
            }

            else -> {}
        }
    }

    fun invalidateObject(entry: BrowserWithPlaywright) {
        pool.invalidateObject(entry)

        when (pool) {
            is GenericObjectPool -> {
                logger.info(
                    "브라우저 인스턴스를 제거합니다: browserId={}, idle={}, active={}, waiting={}",
                    entry.browser.hashCode(), pool.numIdle, pool.numActive, pool.numWaiters,
                )
            }

            else -> {}
        }
    }

    override fun close() {
        pool.close()
    }
}
