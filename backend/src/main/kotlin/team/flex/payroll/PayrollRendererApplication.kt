package team.flex.payroll

import com.fasterxml.jackson.databind.ObjectMapper
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
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayInputStream

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
    private val pdfRenderer: PdfRenderer
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
    fun renderHtmlToPdf(command: HelloCommand): ByteArray? {
        val browser = browserPool.borrowObject()

        val pdfBytes = browser.newContext().use { context ->
            context.newPage().use { page ->
                page.addInitScript("window.initialData = ${objectMapper.writeValueAsString(command)}")
                page.navigate("http://localhost:${serverProperties.port}")
                page.waitForLoadState(LoadState.NETWORKIDLE)
                page.pdf(PdfOptions().setFormat("A4").setPrintBackground(true))
            }
        }

        browserPool.returnObject(browser)

        return pdfBytes
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
