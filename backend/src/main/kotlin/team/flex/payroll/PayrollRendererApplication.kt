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
    // Playwright Java API는 WebSocket 프로토콜로 headless Chromium과 통신한다
    // -> Race Condition 유발 <- Browser 인스턴스 풀링등을 고려해봄직함
    @Bean(destroyMethod = "close")
    fun playwright(): Playwright =
        Playwright.create()

    @Bean(destroyMethod = "close")
    fun browser(playwright: Playwright): Browser =
        playwright.chromium().launch(
            BrowserType.LaunchOptions().setHeadless(true)
        )

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

@Component
class PdfRenderer(
    private val browser: Browser,
    private val serverProperties: ServerProperties,
) {
    fun renderHtmlToPdf(commandId: UUID): ByteArray? {
        val context = browser.newContext()

        val page = context.newPage()
        page.navigate("http://localhost:${serverProperties.port}?commandId=${commandId}")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        val pdfBytes = page.pdf(PdfOptions().setFormat("A4").setPrintBackground(true))

        page.close()
        context.close()

        return pdfBytes
    }
}
