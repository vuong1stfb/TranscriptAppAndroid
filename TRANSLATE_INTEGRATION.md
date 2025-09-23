# Google Translate Integration for Transcript Overlay

## Implementation Summary

This implementation adds Google Translate functionality to the existing transcript overlay system, automatically translating English transcriptions to Vietnamese before displaying them in the subtitle overlay.

## Files Added/Modified

### New Files:
1. **`app/src/main/java/com/example/transcriptapp/model/translate/TranslationResponse.kt`**
   - Data models for Google Translate API response parsing
   - `TranslationResponse`, `Sentence`, `LanguageDetectionResult` classes

2. **`app/src/main/java/com/example/transcriptapp/service/translate/GoogleTranslateService.kt`**
   - Core translation service using Google Translate free endpoint
   - Implements automatic language detection and translation to Vietnamese
   - Graceful error handling with fallback to original text

3. **`app/src/main/java/com/example/transcriptapp/test/TranslateTest.kt`**
   - Test class for verifying translation functionality
   - Demonstrates usage of the translation service

4. **`test_translate_api.sh`**
   - Shell script for testing the Google Translate API endpoint directly

### Modified Files:
1. **`app/src/main/java/com/example/transcriptapp/utils/transcript/TranscriptionManager.kt`**
   - Added `GoogleTranslateService` integration
   - Modified `showSubtitleOverlay()` to translate text before displaying
   - Async translation with coroutines

2. **`app/src/main/java/com/example/transcriptapp/overlay/SubtitleOverlayService.kt`**
   - Added translation capability to `showSubtitle()` method
   - Async translation handling with coroutines
   - Fallback to original text on translation failure

3. **`app/src/main/java/com/example/transcriptapp/MainActivity.kt`**
   - Added test buttons for translation functionality
   - `testTranslationFunction()` for manual testing

4. **`app/src/main/res/layout/activity_main.xml`**
   - Added UI buttons: "Kích hoạt Subtitle Overlay", "Test Translation", "Ẩn Subtitle"

## API Integration Details

### Google Translate Endpoint
```
https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&dj=1&sl=auto&tl=vi&q=TEXT
```

**Parameters:**
- `client=gtx`: Use web client
- `dt=t`: Request translated text
- `dj=1`: JSON response format
- `sl=auto`: Auto-detect source language
- `tl=vi`: Translate to Vietnamese
- `q=TEXT`: Text to translate

**Response Format:**
```json
{
  "sentences": [
    {
      "trans": "Bạn đang nhận thông báo vì bạn đã sửa đổi trạng thái mở/đóng.",
      "orig": "You're receiving notifications because you modified the open/close state.",
      "backend": 1
    }
  ],
  "src": "en",
  "confidence": 0.95,
  "ld_result": {
    "srclangs": ["en"],
    "srclangs_confidences": [0.95],
    "extended_srclangs": ["en"]
  }
}
```

## Key Features

### 1. Automatic Language Detection
- Uses heuristic to determine if translation is needed
- Analyzes text for Latin characters vs. Vietnamese characters
- Avoids unnecessary translation calls for Vietnamese text

### 2. Asynchronous Translation
- Uses Kotlin coroutines for non-blocking translation
- IO dispatcher for network calls
- Main dispatcher for UI updates

### 3. Error Handling
- Graceful fallback to original text on translation failure
- Comprehensive logging for debugging
- Network timeout handling (10s connect, 15s read)

### 4. Integration Points
- **TranscriptionManager**: Translates transcription results before showing overlay
- **SubtitleOverlayService**: Translates any text displayed in subtitle overlay
- **MainActivity**: Test functionality for manual verification

## Usage Examples

### Automatic Integration
When transcription completes, text is automatically translated:
```kotlin
// In TranscriptionManager.showSubtitleOverlay()
val translatedText = if (translateService.isTranslationNeeded(text)) {
    translateService.translateText(text, "vi", "auto")
} else {
    null
}
val displayText = translatedText ?: text
```

### Manual Testing
Use the MainActivity test button to verify translation:
```kotlin
// Test specific text from the problem statement
val testText = "You're receiving notifications because you modified the open/close state."
val translatedText = translateService.translateText(testText, "vi", "auto")
```

### Expected Translation Result
- **Input**: "You're receiving notifications because you modified the open/close state."
- **Output**: "Bạn đang nhận thông báo vì bạn đã sửa đổi trạng thái mở/đóng."

## Testing

### UI Testing
1. Launch the app
2. Grant overlay permission
3. Click "Kích hoạt Subtitle Overlay"
4. Click "Test Translation" to see translation in action
5. Verify Vietnamese text appears in the subtitle overlay

### Code Testing
Run the `TranslateTest.kt` to verify:
- Translation API functionality
- Language detection logic
- Error handling

## Configuration

### Network Requirements
- Internet permission (already configured in AndroidManifest.xml)
- Network connectivity for Google Translate API calls

### Dependencies
- OkHttp3: Network requests
- Gson: JSON parsing
- Kotlin Coroutines: Async operations

All dependencies are already included in the existing project.

## Error Scenarios

1. **No Internet Connection**: Falls back to original text
2. **API Rate Limiting**: Falls back to original text
3. **Invalid Response**: Falls back to original text
4. **Empty Text**: Skips translation, uses original

## Performance Considerations

- Translation requests are cached within the coroutine scope
- Language detection uses simple heuristics to avoid unnecessary API calls
- Timeouts prevent hanging on slow network connections
- Non-blocking UI updates ensure smooth user experience

## Future Enhancements

1. **Language Selection**: Allow users to choose target language
2. **Caching**: Cache translations to reduce API calls
3. **Offline Support**: Integrate local translation models
4. **User Preferences**: Toggle translation on/off
5. **Multiple Languages**: Support translation to multiple languages

## Troubleshooting

### Translation Not Working
1. Check internet connectivity
2. Verify overlay permissions are granted
3. Check logcat for translation errors
4. Test with simple English text first

### UI Issues
1. Ensure subtitle overlay service is started
2. Check overlay permissions
3. Verify broadcast receivers are working

## Implementation Notes

This implementation uses the unofficial Google Translate endpoint which:
- ✅ Requires no API key
- ✅ Free to use
- ✅ Supports many languages
- ⚠️ No official support or SLA
- ⚠️ Rate limiting may apply
- ⚠️ Could change without notice

For production use, consider migrating to official Google Translate API with proper authentication and billing.