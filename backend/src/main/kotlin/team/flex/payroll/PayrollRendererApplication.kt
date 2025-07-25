package team.flex.payroll

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page.PdfOptions
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState
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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.File
import java.io.FileInputStream

@SpringBootApplication
class PayrollRendererApplication {
    @Bean(destroyMethod = "close")
    fun playwright(): Playwright =
        Playwright.create()

    @Bean(destroyMethod = "close")
    fun browser(playwright: Playwright): Browser =
        playwright.chromium().launch(
            BrowserType.LaunchOptions().setHeadless(true)
        )
}

fun main(args: Array<String>) {
    runApplication<PayrollRendererApplication>(*args)
}

@RestController
@RequestMapping("/api/hello")
class HelloController(
    private val pdfRenderer: PdfRenderer
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_PDF_VALUE])
    fun hello(@RequestBody command: HelloCommand): ResponseEntity<InputStreamResource> {
        val pdfFile = File.createTempFile("output-", ".pdf")

        pdfRenderer.renderHtmlToPdf(command.name, pdfFile)

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=hello.pdf")
            .body(InputStreamResource(FileInputStream(pdfFile)))
    }
}

data class HelloCommand(val name: String)

@Component
class PdfRenderer(
    private val browser: Browser,
    private var serverProperties: ServerProperties,
) {
    fun renderHtmlToPdf(nameToGreet: String, pdfFile: File) {
        browser.newPage().apply {
            navigate("http://localhost:${serverProperties.port}?name=${nameToGreet}")

            waitForLoadState(LoadState.NETWORKIDLE)

            pdf(
                PdfOptions()
                    .setPath(pdfFile.toPath())
                    .setFormat("A4")
                    .setPrintBackground(true)
            )
        }
    }
}
