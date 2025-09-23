## Visual Implementation Guide

### MainActivity Interface
The main activity now includes the following buttons (top to bottom):
1. **Cấp quyền Overlay** - Grant overlay permission
2. **Bắt đầu Overlay** - Start recording overlay
3. **Kích hoạt Subtitle Overlay** - Activate subtitle overlay service
4. **Test Subtitle** - Show sample subtitle for testing
5. **Ẩn Subtitle** - Hide current subtitle
6. **Logout** - User logout

### Subtitle Overlay Appearance
- **Position**: Bottom center of screen by default
- **Background**: Semi-transparent white (70% opacity)
- **Text**: Black text, 14sp size, clean typography
- **Behavior**: Draggable to any screen position
- **Size**: Responsive width (120dp minimum, 280dp maximum)

### Example Subtitle Display
```
┌─────────────────────────────────────────────┐
│                 Screen Content              │
│                                             │
│                                             │
│              ┌─────────────────┐            │
│              │ Đây là subtitle │            │ <- Draggable
│              │ mẫu để test tính│            │    overlay
│              │ năng hiển thị   │            │
│              │ phụ đề overlay  │            │
│              └─────────────────┘            │
└─────────────────────────────────────────────┘
```

### Integration Points
1. **TranscriptionManager**: Automatically shows transcription results as subtitles
2. **Broadcast System**: Other components can show/hide subtitles
3. **MainActivity**: Manual control for testing and demonstration
4. **Permission System**: Automatic permission request handling

### Technical Benefits
- ✅ No more disruptive toast notifications
- ✅ Persistent subtitle display until manually hidden
- ✅ User can position subtitle anywhere on screen
- ✅ Professional, modern appearance
- ✅ Ready for Google Translate integration
- ✅ Maintains existing overlay architecture patterns