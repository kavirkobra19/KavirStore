# راه‌اندازی فروشگاه کویر (Kavir Store)

همه مراحل زیر فقط یک‌بار لازمه. بعد از اون، انتشار برنامه جدید یعنی فقط یه Release توی ریپوی همون برنامه.

## ۱) ساخت کلید امضا (فقط یک‌بار، روی کامپیوتر خودت)

> این کلید هویت فروشگاهه. اگه گم بشه یا عوض بشه، گوشی‌ها دیگه نمی‌تونن فروشگاه رو آپدیت کنن (باید حذف و دوباره نصب بشه). یه نسخه پشتیبان امن ازش نگه دار و **هیچ‌وقت داخل ریپو یا چت نذارش**.

```bash
keytool -genkeypair -v -keystore kavir.jks -alias kavir \
  -keyalg RSA -keysize 4096 -validity 36500
```

ازت رمز و چند مشخصات می‌پرسه. رمزها رو یادداشت کن.

## ۲) افزودن Secretها به ریپو

مسیر: ریپو ← **Settings ← Secrets and variables ← Actions ← New repository secret**.

| نام Secret | مقدار |
|---|---|
| `KEYSTORE_BASE64` | خروجی `base64 -w0 kavir.jks` (روی مک: `base64 -i kavir.jks`) |
| `KEYSTORE_PASSWORD` | رمز keystore |
| `KEY_ALIAS` | `kavir` |
| `KEY_PASSWORD` | رمز کلید (اگه موقع ساخت فرق نذاشتی، همون رمز keystore) |

با GitHub CLI هم می‌شه:

```bash
base64 -w0 kavir.jks | gh secret set KEYSTORE_BASE64 -R kavirkobra19/KavirStore
gh secret set KEYSTORE_PASSWORD -R kavirkobra19/KavirStore
gh secret set KEY_ALIAS -R kavirkobra19/KavirStore
gh secret set KEY_PASSWORD -R kavirkobra19/KavirStore
```

`GITHUB_TOKEN` رو لازم نیست بسازی؛ خود GitHub به هر اجرا می‌ده.

## ۳) اولین build و انتشار

ریپو ← **Actions ← Build Kavir Store ← Run workflow** و نسخه رو بنویس (مثلاً `1.0.0`).

این workflow:
1. APK رو با کلید تو امضا می‌کنه و امضا رو با `apksigner` راستی‌آزمایی می‌کنه.
2. `KavirStore-v1.0.0.apk` و فایل `.sha256` رو توی Release با تگ `v1.0.0` منتشر می‌کنه.
3. بعدش `Sync catalog` رو اجرا می‌کنه تا خود فروشگاه هم داخل کاتالوگ بیاد.

اگه Secretها نباشن، فقط یه APK **debug** به‌صورت Artifact ساخته می‌شه و چیزی منتشر نمی‌شه (و یه هشدار می‌بینی).

APK رو از صفحه Releases دانلود و روی گوشی نصب کن. اندروید یه بار اجازه «نصب از این منبع» رو می‌پرسه.

## ۴) ثبت برنامه‌ها

دو راه داری:

**الف) از داخل خود فروشگاه (پنل ادمین)**
1. توی **تنظیمات**، روی بخش «درباره» پنج بار پشت‌سرهم بزن.
2. یه توکن fine-grained بساز: GitHub ← Settings ← Developer settings ← Personal access tokens ← **Fine-grained tokens**
   - Repository access: فقط `KavirStore`
   - Permissions: **Contents: Read and write**, **Actions: Read and write**, (Metadata خودکار read)
   - انقضا: مثلاً ۹۰ روز
3. توکن رو توی صفحه ورود بچسبون. توکن رمزنگاری‌شده و فقط روی همون گوشی ذخیره می‌شه. اگه توکن دسترسی نوشتن به ریپو نداشته باشه، ورود رد می‌شه.
4. با دکمه **+** آدرس ریپوی برنامه رو بده، «تشخیص اطلاعات» رو بزن و «افزودن». بعدش workflow خودش Release رو می‌خونه.

**ب) مستقیم توی فایل** `catalog/sources.json` (هر کی دسترسی نوشتن داره). نمونه:

```json
{ "repo": "kavirkobra19/MyApp", "enabled": true, "featured": false, "category": "tools" }
```

بقیه فیلدها اختیاری‌ان (`name`, `name_fa`, `description`, `description_fa`, `icon`, `screenshots`, `asset_pattern`, `prerelease`). هر چی خالی بمونه خودکار تشخیص داده می‌شه.

## ۵) شرط‌های برنامه‌هایی که ثبت می‌کنی

- ریپو **عمومی** باشه (کاربرها توکن ندارن).
- Release منتشرشده (نه draft) با یه فایل `.apk` **امضاشده** داشته باشه. اگه چند APK هست و می‌خوای مشخص باشه، `asset_pattern` (regex) بگذار.
- `versionCode` با هر نسخه بیشتر بشه، وگرنه اندروید آپدیت رو قبول نمی‌کنه.
- برنامه همیشه با **همون کلید امضا** منتشر بشه.
- نام پکیج (`applicationId`) بین برنامه‌ها یکتا باشه.

## ۶) تشخیص خودکار Release جدید

- هر ساعت workflow `Sync catalog` اجرا می‌شه.
- با تغییر `catalog/sources.json` یا دکمه تازه‌سازی پنل ادمین، بلافاصله اجرا می‌شه.
- اگه می‌خوای فوری باشه، فایل `docs/app-release-hook.yml` رو توی ریپوی اون برنامه کپی کن (نیازمند یه توکن، توضیحش داخل فایل هست).

## ۷) تست روی گوشی (چک‌لیست)

1. نصب فروشگاه از Release، باز کردن، دیدن فهرست و تغییر زبان/تم.
2. نصب یه برنامه: درصد دانلود دیده بشه، صفحه تأیید اندروید باز بشه.
3. یه Release جدید با `versionCode` بالاتر برای اون برنامه بساز، یه ساعت صبر کن (یا Sync رو دستی اجرا کن)، فروشگاه رو تازه کن: دکمه «به‌روزرسانی» و «به‌روزرسانی همه» ظاهر بشه و داده‌های برنامه بعد از آپدیت بمونه.
4. حالت هواپیما: فهرست ذخیره‌شده با پیام آفلاین نمایش داده بشه.

## امنیت، خلاصه

- هیچ توکن یا کلید امضایی داخل APK یا ریپو نیست. کلید فقط توی Secretهای GitHub هست.
- کاربرهای عادی فقط فایل‌های عمومی رو می‌خونن. ادمین بودن یعنی داشتن توکنی که GitHub بهش اجازه نوشتن روی ریپو می‌ده؛ مخفی‌بودن پنل فقط یه راحتیه، نه محافظ.
- قبل از نصب: آدرس باید `https://github.com/` باشه، SHA-256 با کاتالوگ بخونه، نام پکیج و versionCode با کاتالوگ بخونه و برای آپدیت، امضا با برنامه نصب‌شده یکی باشه.
- نصب همیشه از طریق `PackageInstaller` رسمی و با تأیید کاربر انجام می‌شه.
- اگه توکنی رو جایی نوشتی که نباید (مثلاً چت)، همون لحظه توی GitHub ← Settings ← Developer settings پاکش (Revoke) کن.
