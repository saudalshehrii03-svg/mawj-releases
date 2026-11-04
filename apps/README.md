# أدوات موج (Mawj Tools)

تطبيقين أندرويد (Kotlin + Media3)، يتبنون تلقائياً عن طريق GitHub Actions وينزلون في صفحة Releases.

| التطبيق | المجلد | الوظيفة |
|---|---|---|
| **موج HD** (`com.mawj.hdupload`) | `hdupload/` | يعيد تصدير المقطع بدقة 1080p / 2K / 4K ومعدل بت عالي (H.264 أو HEVC)، يحوّل HDR إلى SDR، يحفظه في `Movies/Mawj HD` ويرسله لتيك توك مباشرة. |
| **موج صوت** (`com.mawj.audio`) | `audioextract/` | يستخرج الصوت من أي مقطع: M4A أصلي بدون إعادة ترميز، WAV غير مضغوط، أو AAC. يحفظ في `Music/Mawj Audio`. |

- أقل إصدار أندرويد: 10 (API 29). ما يحتاج أي صلاحيات.
- البناء محلياً: `cd apps && ./gradlew assembleRelease` (يحتاج Android SDK).

## مفتاح التوقيع (اختياري لكن مستحسن)
بدون مفتاح، الـ CI يوقّع بمفتاح debug محفوظ في الكاش (ممكن يتغير لو انحذف الكاش، وساعتها لازم تحذف التطبيق القديم قبل التحديث).
عشان توقيع ثابت دائماً، أضف هذه الـ secrets في إعدادات المستودع:
`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
