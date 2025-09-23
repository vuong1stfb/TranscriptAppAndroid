# Subtitle Overlay Implementation Documentation

## Overview
This implementation replaces the previous toast notification system with a modern overlay-based subtitle display system that is transparent, draggable, and optimized for readability.

## Key Features Implemented

### 1. SubtitleOverlayService
- **Location**: `app/src/main/java/com/example/transcriptapp/overlay/SubtitleOverlayService.kt`
- **Purpose**: Manages the subtitle overlay window that appears on top of other applications
- **Key Features**:
  - Transparent white background (70% opacity: #B3FFFFFF)
  - Draggable functionality using touch events
  - Compact, readable text (14sp with 2dp line spacing)
  - Broadcast receiver for show/hide commands
  - Proper overlay permission handling

### 2. Subtitle Layout
- **Location**: `app/src/main/res/layout/subtitle_overlay.xml`
- **Design**:
  - TextView with transparent white background
  - Max width of 280dp for optimal readability
  - Elevation for visual depth
  - Initially hidden (visibility="gone")

### 3. TranscriptionManager Integration
- **Location**: `app/src/main/java/com/example/transcriptapp/utils/transcript/TranscriptionManager.kt`
- **Changes**:
  - Replaced toast display with subtitle overlay for transcription results
  - Added `showSubtitleOverlay()` method
  - Maintains toast for error messages (appropriate for temporary notifications)

### 4. MainActivity Controls
- **Location**: `app/src/main/java/com/example/transcriptapp/MainActivity.kt`
- **New Controls**:
  - "Kích hoạt Subtitle Overlay" - Starts the subtitle service
  - "Test Subtitle" - Shows sample subtitle text
  - "Ẩn Subtitle" - Hides the current subtitle

## Technical Implementation Details

### Overlay Window Configuration
```kotlin
val params = WindowManager.LayoutParams(
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.WRAP_CONTENT,
    type,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
    PixelFormat.TRANSLUCENT
).apply {
    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    x = 0
    y = 200
}
```

### Drag Functionality
The overlay implements touch event handling to allow users to drag the subtitle to any position on screen:
- ACTION_DOWN: Records initial position and touch coordinates
- ACTION_MOVE: Updates window position based on finger movement
- Uses WindowManager.updateViewLayout() for smooth movement

### Transparency Implementation
- Background color: `#B3FFFFFF` (70% transparent white)
- Text color: Black for maximum contrast and readability
- Elevation: 8dp for visual separation from background content

## Broadcast Communication
The service uses Android's broadcast system for communication:
- `ACTION_SHOW_SUBTITLE`: Display new subtitle text
- `ACTION_HIDE_SUBTITLE`: Hide current subtitle
- `EXTRA_SUBTITLE_TEXT`: Text content to display

## Permission Handling
The service automatically checks for overlay permission and redirects users to settings if not granted.

## Future Google Translate Integration
The current architecture is prepared for translation features:
- Text content is passed as string parameter
- Service can be easily extended to apply translation before display
- Broadcast system allows multiple components to control subtitles

## Usage Example
```kotlin
// Start subtitle service
val intent = Intent(context, SubtitleOverlayService::class.java)
context.startService(intent)

// Show subtitle
val showIntent = Intent(SubtitleOverlayService.ACTION_SHOW_SUBTITLE).apply {
    putExtra(SubtitleOverlayService.EXTRA_SUBTITLE_TEXT, "Your subtitle text here")
}
context.sendBroadcast(showIntent)

// Hide subtitle
val hideIntent = Intent(SubtitleOverlayService.ACTION_HIDE_SUBTITLE)
context.sendBroadcast(hideIntent)
```

## Benefits Over Toast System
1. **Persistent Display**: Subtitles remain visible until manually hidden
2. **User Control**: Draggable to any screen position
3. **Better Readability**: Optimized typography and transparency
4. **Professional Appearance**: Modern overlay design
5. **Extensibility**: Ready for translation and other enhancements

## Files Modified/Created
- ✅ Created: `SubtitleOverlayService.kt`
- ✅ Created: `subtitle_overlay.xml`
- ✅ Modified: `TranscriptionManager.kt`
- ✅ Modified: `MainActivity.kt`
- ✅ Modified: `activity_main.xml`
- ✅ Modified: `AndroidManifest.xml`

This implementation successfully replaces the toast-based subtitle system with a modern, user-friendly overlay solution that meets all requirements and is ready for future enhancements.