# بناء الـ APK بدون تنصيب أي برنامج (من المتصفح)

1. اعمل حساب مجاني على github.com ثم New repository (اسم أي حاجة، Public) ← Create.
2. اضغط "uploading an existing file" وفك الضغط عن الملف واسحب **محتويات** فولدر android-app (مش الفولدر نفسه) ← Commit changes.
3. لو فولدر `.github` مارفعش: اضغط Add file ← Create new file، اكتب الاسم `.github/workflows/build.yml` والصق محتوى `build.yml.txt` ← Commit.
4. افتح تاب Actions ← استنى حوالي 5 دقايق لحد ما يبقى فيه علامة صح خضرا ← افتح آخر تشغيل ← من تحت Artifacts نزّل waziralsaaeat-apk (هيجيلك zip جواه app-debug.apk).
5. ابعت الـ APK لموبايلك وثبّته (اسمح بالتثبيت من مصادر غير معروفة).

ولو التشغيل فشل ✗ ابعتلي نص الخطأ من Actions وأنا أصلحه.
