package com.example.transcriptapp.test

import com.example.transcriptapp.service.translate.GoogleTranslateService
import kotlinx.coroutines.runBlocking

/**
 * Simple test script to verify Google Translate integration
 * This would normally be in the test directory, but created here for quick verification
 */
fun main() {
    val translateService = GoogleTranslateService()
    
    runBlocking {
        println("Testing Google Translate Service...")
        
        // Test 1: English to Vietnamese
        val testText1 = "Hello, how are you today?"
        println("\nTest 1: '$testText1'")
        
        val result1 = translateService.translateText(testText1, "vi", "auto")
        if (result1 != null) {
            println("Translation: $result1")
        } else {
            println("Translation failed")
        }
        
        // Test 2: Check if translation is needed
        val testText2 = "Xin chào, hôm nay bạn thế nào?"
        println("\nTest 2: Is translation needed for '$testText2'?")
        val isNeeded = translateService.isTranslationNeeded(testText2, "vi")
        println("Translation needed: $isNeeded")
        
        // Test 3: Notification message like from the problem statement
        val testText3 = "You're receiving notifications because you modified the open/close state."
        println("\nTest 3: '$testText3'")
        
        val result3 = translateService.translateText(testText3, "vi", "auto")
        if (result3 != null) {
            println("Translation: $result3")
        } else {
            println("Translation failed")
        }
        
        println("\nTest completed!")
    }
}