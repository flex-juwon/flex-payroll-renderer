package team.flex.payroll

import com.fasterxml.jackson.databind.ObjectMapper
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
import java.io.ByteArrayInputStream

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
        // pdf 파일 생성당 5초가 걸리는 상황을 임의로 연출한다
        Thread.sleep(5000)

        val pdfBytes = try {
            pdfRenderer.renderHtmlToPdf(command)
        } catch (e: Exception) {
            throw RuntimeException("Error occurred while rendering pdf", e)
        }

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=hello.pdf")
            .body(InputStreamResource(ByteArrayInputStream(pdfBytes)))
    }
}

data class HelloCommand(val name: String)

@Component
class PdfRenderer(
    private val browser: Browser,
    private val serverProperties: ServerProperties,
    private val objectMapper: ObjectMapper,
) {
    fun renderHtmlToPdf(command: HelloCommand): ByteArray? {
        val context = browser.newContext()

        val pdfBytes = context.newPage().use { page ->
            page.navigate("http://localhost:${serverProperties.port}?name=${command.name}")

            page.waitForLoadState(LoadState.NETWORKIDLE)

            // 템플릿에 바인딩할 데이터를 페이지에 끼워 넣고 다시 렌더링한다
            val data = objectMapper.writeValueAsString(command)
            page.evaluate(DATA_FUNC, data)

            page.pdf(
                PdfOptions()
                    .setFormat("A4")
                    .setPrintBackground(true)
            )
        }

        context.close()

        return pdfBytes
    }

    companion object {
        const val DATA_FUNC = """
            (data) => {
              window.renderData = data;
              if (window.onRenderDataReady) window.onRenderDataReady(data);
            }"""
    }
}
