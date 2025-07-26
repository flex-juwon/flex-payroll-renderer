package team.flex.payroll

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page.PdfOptions
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState
import org.apache.commons.pool2.BasePooledObjectFactory
import org.apache.commons.pool2.ObjectPool
import org.apache.commons.pool2.PooledObject
import org.apache.commons.pool2.PooledObjectFactory
import org.apache.commons.pool2.impl.DefaultPooledObject
import org.apache.commons.pool2.impl.GenericObjectPool
import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.web.ServerProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SpringBootApplication
class PayrollRendererApplication {
    @Bean(destroyMethod = "close")
    fun playwright(): Playwright =
        Playwright.create()

    @Bean
    fun browserPool(playwright: Playwright): BrowserPool =
        BrowserPool(playwright)

    @Bean
    fun commandRegistry(): ConcurrentHashMap<UUID, HelloCommand> =
        // 분산 환경에서 사용할 수 있는 저장소를 흉내낸다(e.g. DB)
        ConcurrentHashMap()
}

fun main(args: Array<String>) {
    runApplication<PayrollRendererApplication>(*args)
}

@RestController
class HelloController(
    private val service: PdfRenderingService
) {
    @PostMapping(
        value = ["/api/hello"],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_PDF_VALUE],
    )
    fun hello(@RequestBody command: HelloCommand): ResponseEntity<InputStreamResource> {
        val pdfBytes = service.render(command)

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=hello.pdf")
            .body(InputStreamResource(ByteArrayInputStream(pdfBytes)))
    }

    @GetMapping("/api/data")
    fun getData(@RequestParam commandId: UUID): ResponseEntity<HelloCommand> =
        service
            .findById(commandId)
            .let { ResponseEntity.ok(it) }
}

data class HelloCommand(val name: String)

@Component
class PdfRenderingService(
    private val repository: HelloCommandRepository,
    private val renderer: PdfRenderer,
) {
    fun render(command: HelloCommand): ByteArray? {
        val commandId = UUID.randomUUID()

        // TODO: renderHtmlToPdf timeout 적용; exception handling(e.g. logging, retry)
        val pdfBytes = try {
            repository.put(commandId, command)
            renderer.renderHtmlToPdf(commandId)
        } finally {
            repository.remove(commandId)
        }

        return pdfBytes
    }

    fun findById(commandId: UUID): HelloCommand? =
        repository.getOrNull(commandId)
}

@Component
class PdfRenderer(
    private val browserPool: BrowserPool,
    private val serverProperties: ServerProperties,
) {
    fun renderHtmlToPdf(commandId: UUID): ByteArray? {
        val browser = browserPool.borrowObject()

        val pdfBytes = browser.newContext().use { context ->
            context.newPage().use { page ->
                page.navigate("http://localhost:${serverProperties.port}?commandId=${commandId}")
                page.waitForLoadState(LoadState.NETWORKIDLE)
                page.pdf(PdfOptions().setFormat("A4").setPrintBackground(true))
            }
        }

        browserPool.returnObject(browser)

        return pdfBytes
    }
}

@Component
class HelloCommandRepository(
    private val commandRegistry: ConcurrentHashMap<UUID, HelloCommand>,
) {
    fun put(commandId: UUID, command: HelloCommand) {
        commandRegistry.putIfAbsent(commandId, command)
    }

    fun getOrNull(commandId: UUID): HelloCommand? {
        return commandRegistry.get(commandId)
    }

    fun remove(commandId: UUID) {
        commandRegistry.remove(commandId)
    }
}

class BrowserPool(
    private val playwright: Playwright,
) {
    private val noOfCpuCores = Runtime.getRuntime().availableProcessors()
    private val pool: ObjectPool<Browser>

    init {
        val factory: PooledObjectFactory<Browser> =
            object : BasePooledObjectFactory<Browser>() {
                override fun create(): Browser =
                    playwright.chromium().launch(
                        // see https://playwright.dev/java/docs/intro
                        // see https://peter.sh/experiments/chromium-command-line-switches/
                        BrowserType.LaunchOptions()
                            .setChannel("chromium")
                            .setHeadless(true)
                            .setArgs(listOf("--disable-gpu"))
                    )

                override fun wrap(browser: Browser?): PooledObject<Browser> =
                    DefaultPooledObject(browser)

                override fun validateObject(p: PooledObject<Browser>): Boolean {
                    val browser = p.`object`
                    if (!browser.isConnected) {
                        return false
                    }

                    return try {
                        browser.newContext().use { context ->
                            context.newPage().use { page ->
                                page.evaluate("1+1") != null
                            }
                        }
                    } catch (e: Exception) {
                        false
                    }
                }
            }

        // TODO: 풀 크기, 최대 대기 시간 등 설정 외부화
        // see org.apache.commons.pool2.impl.GenericObjectPoolConfig
        val config: GenericObjectPoolConfig<Browser> =
            GenericObjectPoolConfig<Browser>().apply {
                minIdle = noOfCpuCores
                maxIdle = minIdle
                testOnCreate = true
                testOnBorrow = true
                testWhileIdle = true
                testOnReturn = true
            }

        pool = GenericObjectPool(factory, config).apply {
            addObjects(noOfCpuCores)
        }
    }

    fun borrowObject(): Browser =
        pool.borrowObject()

    fun returnObject(browser: Browser) {
        pool.returnObject(browser)
    }
}
