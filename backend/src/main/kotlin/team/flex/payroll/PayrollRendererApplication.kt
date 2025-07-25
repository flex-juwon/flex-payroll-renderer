package team.flex.payroll

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

@SpringBootApplication
class PayrollRendererApplication

fun main(args: Array<String>) {
    runApplication<PayrollRendererApplication>(*args)
}

@RestController
@RequestMapping("/hello")
class HelloController(
    private val pdfRenderer: PdfRenderer
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_PDF_VALUE])
    fun hello(@RequestBody command: HelloCommand): ResponseEntity<InputStreamResource> {
        val pdfBytes = pdfRenderer.render(command)
        val content = InputStreamResource(ByteArrayInputStream(pdfBytes))

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=hello.pdf")
            .body(content)
    }
}

data class HelloCommand(val name: String)

@Service
class PdfRenderer {
    fun render(command: HelloCommand): ByteArray {
        val process = ProcessBuilder("ls", "-al")
            .start()

        return process.inputStream.use(InputStream::readAllBytes).also {
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw RuntimeException("Process exited with non-zero exit code: $exitCode")
            }
        }
    }
}
