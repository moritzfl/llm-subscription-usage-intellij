package de.moritzf.proxy.server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiteLlmBudgetErrorTest {
    @Test
    fun recognizesBudgetErrorsByTypeOrMessage() {
        val byType =
            """{"error":{"message":"Budget has been exceeded! Key=my-alias Current cost: 10.0123, Max budget: 10.0","type":"budget_exceeded","param":null,"code":"422"}}"""
        val byMessage =
            """{"error":{"message":"ExceededBudget: Key over 1d budget. Spend=${'$'}5.0012, Limit=${'$'}5.00","type":"auth_error","code":"422"}}"""
        val team =
            """{"error":{"message":"Budget has been exceeded! Team=platform Current cost: 3, Max budget: 2","type":"invalid_request_error"}}"""

        assertTrue(LiteLlmBudgetError.isBudgetExceeded(422, byType))
        assertTrue(LiteLlmBudgetError.isBudgetExceeded(429, byType), "LiteLLM before 1.104")
        assertTrue(LiteLlmBudgetError.isBudgetExceeded(422, byMessage))
        assertTrue(LiteLlmBudgetError.isBudgetExceeded(422, team))
    }

    @Test
    fun ignoresValidationErrorsAndOtherStatuses() {
        val budget =
            """{"error":{"message":"Budget has been exceeded!","type":"budget_exceeded"}}"""

        assertFalse(
            LiteLlmBudgetError.isBudgetExceeded(
                422,
                """{"detail":[{"loc":["body","model"],"msg":"field required"}]}""",
            )
        )
        assertFalse(
            LiteLlmBudgetError.isBudgetExceeded(
                422,
                """{"error":{"message":"Invalid model","type":"invalid_request_error"}}""",
            )
        )
        assertFalse(LiteLlmBudgetError.isBudgetExceeded(400, budget))
        assertFalse(LiteLlmBudgetError.isBudgetExceeded(500, budget))
        assertFalse(LiteLlmBudgetError.isBudgetExceeded(422, null))
        assertFalse(LiteLlmBudgetError.isBudgetExceeded(422, "Budget has been exceeded!"))
    }
}
