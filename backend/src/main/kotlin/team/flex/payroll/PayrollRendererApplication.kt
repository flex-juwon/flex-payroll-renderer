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
import java.time.Duration

@SpringBootApplication
class PayrollRendererApplication {
    @Bean(destroyMethod = "close")
    fun playwright(): Playwright =
        Playwright.create()

    @Bean
    fun browserPool(playwright: Playwright): BrowserPool =
        BrowserPool(playwright)
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

    fun renderHtmlToPdf(command: HelloCommand): ByteArray? {
        val maxAttempts = 3
        val initialDelayMillis = 1000L
        val backoffMultiplier = 2

        var attempt = 0
        var delayMillis = initialDelayMillis

        while (true) {
            try {
                return doRender(command)
            } catch (e: PlaywrightException) {
                attempt++

                if (attempt >= maxAttempts) {
                    throw e
                }

                Thread.sleep(delayMillis)
                delayMillis *= backoffMultiplier

                logger.warn("pdf 렌더링 작업을 ${delayMillis}ms 후에 재시도합니다: attempt=${attempt}/${maxAttempts}")
            }
        }
    }

    private fun doRender(command: HelloCommand): ByteArray? {
        val data = objectMapper.writeValueAsString(command)
        val browser = browserPool.borrowObject()

        val pdfBytes = browser.newContext().use { context ->
            context.newPage().use { page ->
                page.addInitScript("window.initialData = $data")
                page.navigate("http://localhost:${serverProperties.port}")
                page.waitForLoadState(LoadState.NETWORKIDLE)

                logger.info(
                    "html 페이지를 렌더링했습니다. pdf 렌더링을 시작합니다: browserId={}, contextId={}, pageId={}, data={}",
                    browser.hashCode(), context.hashCode(), page.hashCode(), data
                )

                page.pdf(PdfOptions().setFormat("A4").setPrintBackground(true))
            }
        }

        browserPool.returnObject(browser)

        return pdfBytes
    }
}

class BrowserPool(
    playwright: Playwright,
) {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    private val poolSize = Runtime.getRuntime().availableProcessors().coerceAtMost(8).coerceAtLeast(2)
    private val pool: ObjectPool<Browser>

    init {
        val factory: PooledObjectFactory<Browser> =
            object : BasePooledObjectFactory<Browser>() {
                private val logger: Logger = LoggerFactory.getLogger(javaClass)

                override fun create(): Browser {
                    val browser = playwright.chromium().launch(
                        // see https://playwright.dev/java/docs/intro
                        // see https://peter.sh/experiments/chromium-command-line-switches/
                        BrowserType.LaunchOptions()
                            .setChannel("chromium")
                            .setHeadless(true)
                            .setArgs(listOf("--disable-gpu"))
                    )

                    logger.info("새 브라우저 인스턴스를 생성했습니다: browserId={}", browser.hashCode())

                    return browser
                }

                override fun wrap(browser: Browser?): PooledObject<Browser> =
                    DefaultPooledObject(browser)

                override fun validateObject(pooledObject: PooledObject<Browser>): Boolean {
                    val browser = pooledObject.`object`
                    if (!browser.isConnected) {
                        logger.warn("브라우저 인스턴스와 연결이 끊어졌습니다: browserId={}", browser.hashCode())
                        return false
                    }

                    return try {
                        browser.newContext().use { context ->
                            context.newPage().use { page ->
                                page.evaluate("1+1") != null
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("브라우저 인스턴스가 비정상 상태입니다: errorMessage={}", e.message)
                        false
                    }
                }
            }

        // see org.apache.commons.pool2.impl.GenericObjectPoolConfig
        val config: GenericObjectPoolConfig<Browser> =
            GenericObjectPoolConfig<Browser>().apply {
                minIdle = poolSize
                maxIdle = poolSize
                maxTotal = poolSize
                testOnCreate = true
                testOnBorrow = true
                testWhileIdle = true
                setMaxWait(Duration.ofMillis(3000L))
            }

        pool = GenericObjectPool(factory, config).apply {
            addObjects(poolSize)
        }

        logger.info("브라우저 풀이 준비됐습니다: numIdle={}, numActive={}", pool.numIdle, pool.numActive)
    }

    fun borrowObject(): Browser {
        val browser = pool.borrowObject()

        logger.info(
            "브라우저 인스턴스를 구했습니다: browserId={}, numIdle={}, numActive={}",
            browser.hashCode(), pool.numIdle, pool.numActive
        )

        return browser
    }

    fun returnObject(browser: Browser) {
        pool.returnObject(browser)

        logger.info(
            "브라우저 인스턴스를 반납했습니다: browserId={}, numIdle={}, numActive={}",
            browser.hashCode(), pool.numIdle, pool.numActive
        )
    }
}
