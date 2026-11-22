# ExKMPTracker integration / Підключення ExKMPTracker

Library / Бібліотека: <https://github.com/vsvictor/ExKMPTracker>

**EN.** This guide covers integration into other Android, Kotlin Multiplatform and
Swift applications. Examples target library version `1.1.6`.
Changes since 1.0.0 are in [changelog.md](changelog.md). The host application
owns authentication, permission requests, networking and lifecycle decisions.
ExKMPTracker supplies positioning, sensor fusion, native background adapters and
an encrypted delivery queue.

**UK.** Інструкція описує підключення до інших Android-, Kotlin Multiplatform-
і Swift-застосунків. Приклади відповідають версії бібліотеки `1.1.6`.
Зміни відносно 1.0.0 зібрано в [changelog.uk.md](changelog.uk.md).
Хост керує авторизацією, запитами дозволів, мережею та життєвим циклом.
ExKMPTracker надає позиціювання, об'єднання сенсорних даних, нативні адаптери
фонової роботи та зашифровану чергу доставки.

## Mapbox in XRNavi / Mapbox у XRNavi

**EN.** In this project all Mapbox maps use `NativeMap` with the `PlatformMapFactory`
owned by `AppDependencies`. Do not instantiate an independent platform location provider
for Mapbox. `TrackerLocationBloc` projects the authenticated runtime's estimates into one
shared fix stream; `TrackingLocationProvider` supplies the same stream to `GeoBloc`
for recentering and the current-location route origin. Opening the signed-in map starts
observation automatically without another permission prompt or a Locate-button press.

Android injects `TrackerMapboxLocationProvider` into `mapView.location` before enabling
the native puck. iOS sets `mapView.location.dataModel` to a `LocationDataModel`
with a tracker-backed Combine location publisher and an empty heading publisher,
then enables a course-oriented puck.
The platform adapters forward position and accuracy/course (plus original observation time
and speed on iOS, whose SDK location model supports them). They do not sample GPS or compass.
No separate blue user-pin layer is drawn. Null fixes hide the native puck;
stopping/logout, missing estimates, lost permissions/services and the 60-second
freshness deadline invalidate the shared position. Tracker-only mode also applies before
registration succeeds: no raw-platform fallback is used.
Adding another Mapbox screen must reuse this entry point and dependency injection,
not initialize the SDK's default location provider.

**UK.** У цьому проєкті всі мапи Mapbox використовують `NativeMap` із `PlatformMapFactory`,
якою володіє `AppDependencies`. Не створюйте окремого платформного постачальника геолокації
для Mapbox. `TrackerLocationBloc` перетворює оцінки авторизованого трекера на спільний
потік позиції; `TrackingLocationProvider` передає цей самий потік у `GeoBloc`
для центрування й початку маршруту «Моє місце». Відкриття авторизованої мапи автоматично
починає спостереження без повторного запиту дозволу та натискання кнопки позиціювання.

Android передає `TrackerMapboxLocationProvider` у `mapView.location` до ввімкнення
нативного індикатора. iOS задає `mapView.location.dataModel` через `LocationDataModel`
із Combine-потоком позиції трекера й порожнім потоком компаса та вмикає індикатор
із напрямком за курсом.
Адаптери передають координати, точність і курс; iOS також отримує первинний час
вимірювання та швидкість, які підтримує модель позиції його SDK.
Окремого збору GPS чи компаса немає. Дублюючий синій маркер користувача не малюється.
Значення null приховує індикатор; зупинка/вихід, відсутність оцінки, втрата дозволу
чи геосервісів та перевищення віку 60 секунд скидають спільну позицію.
До успішної реєстрації також використовується лише трекер, без запасного raw-GPS.
Кожен новий екран Mapbox має повторно використовувати цю точку входу та DI,
а не ініціалізувати стандартне джерело геолокації SDK.

## 1. Git and Gradle / Git і Gradle

**EN.** Run in the host repository root. The submodule pins a specific library
commit; `1.1.6` below is the composite-build coordinate, not a claim that the
artifact is published to Maven Central.

**UK.** Виконайте в корені репозиторію хоста. Submodule фіксує конкретний commit
бібліотеки; `1.1.6` нижче — координата composite build, а не твердження про
публікацію артефакту в Maven Central.

```bash
git submodule add \
  https://github.com/vsvictor/ExKMPTracker.git \
  external/ExKMPTracker
```

`settings.gradle.kts`:

```kotlin
val trackerBuild = "external/ExKMPTracker"
require(file(trackerBuild).resolve("settings.gradle.kts").isFile) {
    "Initialize submodules: git submodule update --init --recursive"
}
includeBuild(trackerBuild)
```

**EN.** Add to the shared KMP module:

**UK.** Додайте до спільного KMP-модуля:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.exkmptracker:tracker:1.1.6")
        }
    }
}
```

**EN.** Add the same dependency to the Android application module if it directly
uses the native service APIs. For an Android-only project, this is sufficient:

**UK.** Додайте таку саму залежність до Android-модуля застосунку, якщо він напряму
використовує API сервісу. Для суто Android-проєкту достатньо цього:

```kotlin
dependencies {
    implementation("com.exkmptracker:tracker:1.1.6")
}
```

**EN.** The current library uses Kotlin `2.4.20`, AGP `9.1.1`, JDK 21 and Android
SDK 37; align the host toolchain accordingly. Set `ANDROID_HOME`, or configure
`sdk.dir` in the ignored `local.properties` of both the host and the submodule.
Apple builds additionally require macOS and Xcode.

**UK.** Поточна бібліотека використовує Kotlin `2.4.20`, AGP `9.1.1`, JDK 21 та
Android SDK 37; узгодьте інструменти хоста. Задайте `ANDROID_HOME` або вкажіть
`sdk.dir` в ігнорованих `local.properties` хоста й submodule.
Для Apple-збірок додатково потрібні macOS та Xcode.

```bash
# New checkout / Новий checkout
git clone --recurse-submodules <host-repository-url>

# Existing checkout / Наявний checkout
git submodule update --init --recursive
```

**EN.** To update, fetch the library and explicitly check out a reviewed commit
or tag inside `external/ExKMPTracker`, then record the changed gitlink through the
host project's normal review process. Do not silently track the latest branch.

**UK.** Для оновлення виконайте fetch бібліотеки та явно виберіть перевірений
commit або tag у `external/ExKMPTracker`. Зміну gitlink оформлюйте за звичним
процесом перевірки хоста. Не оновлюйте автоматично до останнього стану гілки.

## 2. Host-owned transport / Транспорт хоста

**EN.** The core does not open a network connection. Inject an adapter over the
host's existing authenticated RPC connection. This reusable adapter belongs in
its own file, `AppTrackingRpcTransport.kt`:

**UK.** Ядро не відкриває мережевого з'єднання. Передайте адаптер наявного
автентифікованого RPC-з'єднання хоста. Цей адаптер розміщується в окремому файлі
`AppTrackingRpcTransport.kt`:

```kotlin
import com.exkmptracker.ports.TrackingRpcTransport
import kotlinx.serialization.json.JsonObject

class AppTrackingRpcTransport(
    private val authorizedCall: suspend (String, JsonObject) -> JsonObject,
) : TrackingRpcTransport {
    override suspend fun call(
        method: String,
        params: JsonObject,
    ): JsonObject = authorizedCall(method, params)

    override suspend fun notify(method: String, params: JsonObject): Unit =
        error("Tracking requires acknowledged RPC calls")
}
```

**EN.** At the host's DI composition root:

**UK.** У точці складання DI хоста:

```kotlin
val transport = RpcTrackingTransport(
    AppTrackingRpcTransport(authorizedCall),
)
```

**EN.** Import `com.exkmptracker.transport.RpcTrackingTransport`.
`authorizedCall` is a host-provided function, not a library API: it adds the
current token, performs refresh/retry, and returns the decoded JSON-RPC `result`
object, not the response envelope. It must throw on failures and propagate
cancellation. Keep tokens out of logs. CoopNav RPC uses authenticated QUIC.
The host retains ownership of the connection.

**UK.** Імпортуйте `com.exkmptracker.transport.RpcTrackingTransport`.
`authorizedCall` — функція хоста, а не API бібліотеки: вона додає актуальний
токен, виконує refresh/retry та повертає декодований об'єкт JSON-RPC `result`,
а не всю відповідь. Помилки мають передаватися як винятки, скасування —
поширюватися далі. Не записуйте токени в журнали. CoopNav RPC використовує
автентифікований QUIC. З'єднання залишається у власності хоста.

**EN.** A custom backend can instead implement
`com.exkmptracker.ports.TrackingTransport` directly:

**UK.** Для власного бекенду можна натомість реалізувати
`com.exkmptracker.ports.TrackingTransport`:

```kotlin
interface TrackingTransport {
    suspend fun serverTime(session: TrackingSession): ServerTime
    suspend fun ingest(
        session: TrackingSession,
        points: List<TrackPoint>,
    ): List<String>
}
```

**EN.** This is the existing library interface signature, not a new interface to
declare in the host. Return only IDs durably accepted by the server; retries
must be idempotent. Return authenticated server time in Unix milliseconds.
Stage 2 additionally needs `CooperativeTransport`; `RpcTrackingTransport`
already implements it.

**UK.** Це сигнатура наявного інтерфейсу бібліотеки, а не новий інтерфейс для
оголошення в хості. Повертайте лише ID надійно прийнятих сервером точок;
повторна доставка має бути ідемпотентною. Час автентифікованого сервера
повертається в Unix-мілісекундах. Для етапу 2 додатково потрібен
`CooperativeTransport`; `RpcTrackingTransport` його вже реалізує.

## 3. Confirmed session and BLoC / Підтверджена сесія та BLoC

**EN.** Obtain the session and identity from the authenticated backend before
starting collection. Do not invent a driver, brand or session ID on the client.

**UK.** До запуску збору отримайте сесію та ідентичність від автентифікованого
бекенду. Не вигадуйте ID водія, організації або сесії на клієнті.

```kotlin
import com.exkmptracker.model.TrackingIdentity
import com.exkmptracker.model.TrackingSession

val session = TrackingSession(
    sessionId = confirmedSessionId,
    identity = TrackingIdentity(
        driverId = confirmedDriverId,
        brandId = confirmedBrandId,
    ),
)
```

**EN.** Use an application-owned BLoC and injected runtime/transport, not a
singleton or a screen-owned tracker. The lifecycle should be:

**UK.** Використовуйте BLoC рівня застосунку та інжектовані runtime/transport,
а не singleton чи трекер, яким володіє екран. Життєвий цикл:

```text
Login / Restore
  -> confirm server session
  -> obtain OS permissions
  -> start native runtime
  -> observe state and expose errors

Logout / rejected authentication / owner change
  -> cancel pending registration and starts
  -> await old runtime stop
  -> only then allow another owner to start
```

**EN.** Ordinary token refresh must not restart collection. Entering the
background or closing the map must not stop it. Observe `TrackerState.running`,
`estimate`, `error` and `rejection`; do not present unavailable or stale
coordinates as fresh fixes.

**UK.** Звичайне оновлення токенів не повинно перезапускати збір. Перехід
у фон чи закриття мапи не повинні його зупиняти. Спостерігайте за
`TrackerState.running`, `estimate`, `error` і `rejection`; не показуйте
недоступні або застарілі координати як свіжі.

## 4. Android Foreground Service

**EN.** Make the host's existing `Application` implement
`AndroidTrackerProvider`. The following members go inside that class;
`appGraph` represents your application-owned DI graph:

**UK.** Наявний `Application` хоста має реалізувати `AndroidTrackerProvider`.
Наведені члени додаються до цього класу; `appGraph` означає ваш DI-граф
рівня застосунку:

```kotlin
override fun createTrackingTransport(
    context: Context,
): TrackingTransport = appGraph.trackingTransport

override fun allowsStickyRestore(context: Context): Boolean = false
```

**EN.** Import the provider from `com.exkmptracker.android`, `Context` from
`android.content`, and `TrackingTransport` from `com.exkmptracker.ports`.
Returning `false` requires fresh host confirmation after process death; it
does not disable ordinary background collection.

**UK.** Provider імпортується з `com.exkmptracker.android`, `Context` —
з `android.content`, `TrackingTransport` — з `com.exkmptracker.ports`.
Значення `false` вимагає нового підтвердження хостом після завершення процесу;
звичайний фоновий збір воно не вимикає.

**EN.** In the existing manifest's `<application>` element:

**UK.** У наявному елементі `<application>` маніфесту:

```xml
android:name=".YourApplication"
android:allowBackup="false"
```

**EN.** The library manifest supplies the service and permission declarations.
The host requests runtime permissions in stages: foreground location first,
background location separately on Android 10+, notifications on Android 13+.
Request precise location for the intended accuracy. Activity recognition is
optional; denial disables that advisory input, not location collection.
`AndroidTracker.permissionsForHost()` is a checklist, not one combined prompt.

**UK.** Маніфест бібліотеки додає сервіс і декларації дозволів.
Хост запитує runtime-дозволи поетапно: спочатку foreground-геолокацію,
окремо фонову на Android 10+, сповіщення на Android 13+.
Для потрібної точності запитуйте точну геолокацію. Розпізнавання активності
необов'язкове: відмова вимикає лише цей допоміжний сигнал.
`AndroidTracker.permissionsForHost()` — перелік, а не один спільний запит.

**EN.** Start only from a visible, resumed Activity with window focus, after
the server confirms the session and permissions are granted:

**UK.** Запускайте лише з видимої resumed Activity із фокусом вікна після
підтвердження сесії сервером та отримання дозволів:

```kotlin
AndroidTracker.startConfirmedShift(activity, session)
```

**EN.** Import `com.exkmptracker.android.AndroidTracker`. Do not start from
boot receivers, workers or arbitrary background tasks.
Bind to the already-started service without `BIND_AUTO_CREATE`:

**UK.** Імпортуйте `com.exkmptracker.android.AndroidTracker`. Не запускайте
з boot receiver, worker чи довільного фонового завдання.
Підключайтеся до вже запущеного сервісу без `BIND_AUTO_CREATE`:

```kotlin
val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        val binder = service as TrackerForegroundService.TrackerBinder
        appScope.launch {
            binder.state.collect { state -> onTrackerState(state) }
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        onTrackerDisconnected()
    }
}

check(AndroidTracker.bind(activity, connection)) {
    "Tracker service is not yet available"
}
```

**EN.** This is an integration fragment: `appScope`, `onTrackerState` and
`onTrackerDisconnected` belong to the host's lifecycle bridge/BLoC. Retain the
binder and observer job there, cancel observation and unbind when the bridge is
released. Service startup is asynchronous: handle a failed bind with a bounded,
cancellable retry instead of assuming it is immediately available.

**UK.** Це фрагмент інтеграції: `appScope`, `onTrackerState` та
`onTrackerDisconnected` належать lifecycle-адаптеру/BLoC хоста. Зберігайте там
binder і job спостереження; при звільненні адаптера скасуйте спостереження
та виконайте unbind. Запуск сервісу асинхронний: невдале підключення потребує
обмеженого повтору з підтримкою скасування, а не припущення про миттєву готовність.

**EN.** On logout, await shutdown from a coroutine, including in the background:

**UK.** Після виходу очікуйте зупинки в coroutine, зокрема у фоні:

```kotlin
binder.stopTracking()
```

**EN.** If the binder is unavailable:

**UK.** Якщо binder недоступний:

```kotlin
AndroidTracker.stopFromHost(context)
```

## 5. iOS through KMP / iOS через KMP

**EN.** Create one application-owned runtime and inject the transport:

**UK.** Створіть runtime рівня застосунку та передайте транспорт:

```kotlin
import com.exkmptracker.ios.IosTrackerRuntime

val runtime = IosTrackerRuntime(transport)

// From a coroutine after permissions / Із coroutine після отримання дозволів
runtime.startConfirmedSession(
    session = session,
    backgroundLocationConfigured = false,
)

// Observe through the host BLoC / Спостерігайте через BLoC хоста
runtime.state.collect { state -> onTrackerState(state) }

// Separate logout handler / Окремий обробник виходу
runtime.stop()
```

**EN.** The collection and logout snippets run in separate lifecycle jobs:
`collect` does not complete by itself. Retain and cancel the observation job.
Do not call `shutdown()` on background entry; it permanently shuts down that
runtime instance. `stop()` ends the session while allowing a later start.

**UK.** Спостереження та обробник виходу виконуються в окремих lifecycle jobs:
`collect` самостійно не завершується. Зберігайте та скасовуйте job спостереження.
Не викликайте `shutdown()` при переході у фон: він остаточно закриває цей
екземпляр runtime. `stop()` завершує сесію, дозволяючи наступний запуск.

**EN.** Add the following to the app's `Info.plist`. These bilingual sample
purpose strings may instead be localized through `InfoPlist.strings`:

**UK.** Додайте до `Info.plist` застосунку. Наведені двомовні описи призначення
можна натомість локалізувати через `InfoPlist.strings`:

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>Location records your trip. / Геолокація потрібна для запису поїздки.</string>
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>Location records your trip in the background. / Геолокація потрібна для запису поїздки у фоні.</string>
<key>NSMotionUsageDescription</key>
<string>Motion improves positioning. / Дані руху уточнюють позицію.</string>
<key>UIBackgroundModes</key>
<array>
    <string>location</string>
</array>
```

**EN.** XRNavi requests When In Use only; it does not request Always. The library
never requests permissions itself. XRNavi passes `backgroundLocationConfigured = true`
only when Always has already been granted and the required plist configuration is present.
When In Use is foreground-only; it is not reported as an error.

**UK.** XRNavi запитує лише When In Use, без запиту Always. Бібліотека сама не
запитує дозволів. XRNavi передає `backgroundLocationConfigured = true` лише коли
Always уже надано і в plist є потрібні налаштування. When In Use забезпечує роботу
на передньому плані й не вважається помилкою.

## 6. Native Swift host / Нативний Swift-хост

**EN.** Build on macOS, then add the resulting `ExKMPTracker.xcframework` from
`tracker/build/XCFrameworks/release/` to the Xcode application target:

**UK.** Зберіть на macOS та додайте отриманий `ExKMPTracker.xcframework`
із `tracker/build/XCFrameworks/release/` до target застосунку Xcode:

```bash
cd external/ExKMPTracker
./gradlew :tracker:assembleExKMPTrackerReleaseXCFramework
```

```swift
import ExKMPTracker

let transport = HostRpcTrackingTransport(client: hostRpcClient)
let runtime = IosTrackerRuntime(transport: transport)

let identity = try TrackingIdentity(driverId: driverID, brandId: brandID)
let session = try TrackingSession(
    sessionId: confirmedSessionID,
    identity: identity
)
try await runtime.startConfirmedSession(
    session: session,
    backgroundLocationConfigured: false
)

let observation = runtime.observeState { state in
    onTrackerState(state)
}

// In the logout handler / В обробнику виходу
observation.cancel()
try await runtime.stop()
```

**EN.** `hostRpcClient` is the host's implementation of the exported
`TrackingRpcClient` protocol. It uses the existing authenticated connection and
supports cancellation. Retain the runtime and observation handle in the host's
DI/lifecycle owner. Frameworks are static and support `iosArm64` and
`iosSimulatorArm64`; inspect generated headers for exact Swift bridge signatures.

**UK.** `hostRpcClient` — реалізація експортованого протоколу `TrackingRpcClient`
у хості. Вона використовує наявне автентифіковане з'єднання й підтримує скасування.
Зберігайте runtime і handle спостереження у DI/lifecycle-власнику хоста.
Фреймворки статичні та підтримують `iosArm64` і `iosSimulatorArm64`;
точні сигнатури Swift-моста наведені в згенерованих заголовках.

## 7. Backend and installation ID / Бекенд та ID інсталяції

**EN.** With `RpcTrackingTransport`, the backend must implement:

**UK.** Для `RpcTrackingTransport` бекенд має реалізувати:

| Method / Метод | Business parameters / Бізнес-параметри | Result / Результат |
|---|---|---|
| `serverTime` | `{sessionId}` | `{serverTimeMs}` |
| `tracking.ingest` | `{sessionId, driverId, brandId, points}` | `{acceptedIds}` |

**EN.** The host adds authentication. Ingest batches contain 1–100 points;
the server verifies ownership and handles duplicate deliveries idempotently.
The separate ExServerTracker server library supplies the compatible CoopNav
plugin, but the host still needs authentication and persistent storage wiring.

**UK.** Хост додає автентифікацію. Пакет містить 1–100 точок;
сервер перевіряє власника та ідемпотентно обробляє повторну доставку.
Окрема серверна бібліотека ExServerTracker надає сумісний CoopNav-плагін,
але хост має підключити авторизацію та постійне сховище.

**EN.** `tracking.open` is an XRNavi/BRNavi integration endpoint, not a mandatory
method of ExKMPTracker. In XRNavi, the existing securely stored login `deviceId`
is reused as the permanent installation `trackerId`. BRNavi persists its
user/device tracking grant in MongoDB:

**UK.** `tracking.open` — endpoint інтеграції XRNavi/BRNavi, а не обов'язковий
метод ExKMPTracker. XRNavi повторно використовує наявний захищений `deviceId`
входу як постійний `trackerId` інсталяції. BRNavi зберігає прив'язку
користувача/пристрою до трекінгу в MongoDB:

```text
tracking.open({deviceId})
  -> {trackerId, sessionId, driverId, brandId, refreshRequired?}
```

**EN.** If `refreshRequired` is true, XRNavi refreshes and securely persists
both OAuth tokens before starting collection. Other projects may use their own
registration API and identity policy. The tracker library alone does not
register installation IDs in a remote database.

**UK.** Якщо `refreshRequired` дорівнює true, XRNavi оновлює та безпечно
зберігає обидва OAuth-токени до запуску збору. Інші проєкти можуть мати власний
API реєстрації та політику ідентичності. Сама бібліотека трекера не реєструє
ID інсталяції у віддаленій БД.

## 8. Sections / Етапи

**EN.** The BLoC, native runtimes and Android service binder expose the
suspending `section(number, enabled)` API. Defaults:

**UK.** BLoC, нативні runtime та binder Android-сервісу мають suspend-метод
`section(number, enabled)`. Типові значення:

```kotlin
tracker.section(1, true)
tracker.section(2, false)
tracker.section(3, false)
```

**EN.** Here `tracker` is the runtime or service binder, not a global instance.
Disabling stage 2 returns live autonomous estimates. Disabling stage 1 also
disables stage 2, passes native fixes through and stops filtering, recording and
upload. Stage 2 requires injected vision, a licensed model, camera calibration,
timestamped frames and server support. Stage 3 cannot be enabled yet.

**UK.** Тут `tracker` — runtime або binder сервісу, а не глобальний екземпляр.
Вимкнення етапу 2 повертає живі автономні оцінки. Вимкнення етапу 1 вимикає
також етап 2, пропускає нативні координати та зупиняє фільтрацію, запис і
надсилання. Етап 2 потребує інжектованого vision-адаптера, ліцензованої моделі,
калібрування камери, кадрів із часовими позначками та серверної підтримки.
Етап 3 поки не можна ввімкнути.

## 9. Integration checklist / Перевірка інтеграції

**EN.** Verify on physical devices: first permission grant and denial,
foreground/background operation, logout while registration or permissions are
pending, account switching, expired authentication, network loss and queued
delivery after reconnection. Record original device settings before changing
them and restore them afterward. A successful build alone does not establish
background longevity or positioning accuracy.

**UK.** Перевірте на фізичних пристроях: перше надання дозволів і відмову,
foreground/background-роботу, вихід під час реєстрації чи очікування дозволів,
зміну акаунта, завершення авторизації, втрату мережі та доставку черги після
відновлення зв'язку. Перед зміною параметрів пристрою запишіть початкові значення
та відновіть їх після перевірок. Успішна збірка сама по собі не підтверджує
тривалість фонової роботи чи точність позиціювання.

**EN.** Android force-stop, iOS force-quit, system/OEM restrictions and revoked
permissions may stop tracking. Neither platform guarantees uninterrupted
execution or exact 1 Hz delivery. Camera-based stage 2 has additional background
restrictions, especially on iOS. Disclose background collection and comply with
store/privacy requirements.

**UK.** Android force-stop, iOS force-quit, системні обмеження, політики виробника
й відкликані дозволи можуть зупинити трекінг. Жодна платформа не гарантує
безперервного виконання чи точної доставки 1 Гц. Камерний етап 2 має додаткові
фонові обмеження, особливо на iOS. Пояснюйте фоновий збір користувачу та
дотримуйтеся вимог приватності й магазинів застосунків.

## References / Посилання

- [Android integration / Інтеграція Android](https://github.com/vsvictor/ExKMPTracker/blob/main/docs/android.md)
  · [Українською](https://github.com/vsvictor/ExKMPTracker/blob/main/docs/android.uk.md)
- [iOS integration / Інтеграція iOS](https://github.com/vsvictor/ExKMPTracker/blob/main/docs/ios.md)
  · [Українською](https://github.com/vsvictor/ExKMPTracker/blob/main/docs/ios.uk.md)
- [CoopNav contract / Контракт CoopNav](https://github.com/vsvictor/ExKMPTracker/blob/main/docs/coopnav.md)
  · [Українською](https://github.com/vsvictor/ExKMPTracker/blob/main/docs/coopnav.uk.md)

**EN.** GitHub `main` documentation may evolve; use the pinned submodule's
`docs/` for the exact APIs of the revision included in your application.

**UK.** Документація GitHub `main` може змінюватися; точні API підключеної
ревізії перевіряйте в `docs/` зафіксованого submodule.
