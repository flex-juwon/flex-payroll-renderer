package team.flex.payroll

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PayrollRendererApplication

fun main(args: Array<String>) {
    runApplication<PayrollRendererApplication>(*args)
}
