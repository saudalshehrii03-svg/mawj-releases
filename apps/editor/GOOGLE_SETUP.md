# تفعيل تسجيل الدخول بجوجل في «موج مونتاج» (مرة وحدة بس)

التطبيق يحفظ مشاريعك في مساحة خاصة فيه داخل Google Drive (`appDataFolder`)، ما يشوفها أحد غيرك وما تخرب ملفات الـ Drive عندك.
عشان جوجل تسمح للتطبيق بتسجيل الدخول، لازم تسجّله في Google Cloud مرة وحدة (مجاني):

1. افتح https://console.cloud.google.com وسجّل دخول بحسابك.
2. أنشئ مشروع جديد (مثلاً: Mawj).
3. من **APIs & Services → Library** ابحث عن **Google Drive API** واضغط **Enable**.
4. من **APIs & Services → OAuth consent screen** (أو Google Auth Platform):
   - النوع: **External**، اسم التطبيق: موج مونتاج، وإيميلك.
   - في **Audience / Test users** أضف إيميلك (وأي إيميل ثاني بتستخدمه).
5. من **APIs & Services → Credentials → Create credentials → OAuth client ID**:
   - Application type: **Android**
   - Package name: `com.mawj.editor`
   - SHA-1: انسخه من وصف آخر إصدار في صفحة Releases (مكتوب «بصمة التوقيع SHA-1»).
6. احفظ. بعد دقايق، افتح التطبيق ← «حساب جوجل» ← تسجيل الدخول.

> إذا تغيّرت بصمة SHA-1 (مثلاً بعد ما تضيف مفتاح توقيع جديد)، حدّثها في نفس الصفحة.
