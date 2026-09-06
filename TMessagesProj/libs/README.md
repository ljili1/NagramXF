# libs — native dependencies

`build.gradle` declares:

    implementation files('libs/libbox.aar')

Before building, place the **sing-box `libbox` AAR** in this directory as
`libbox.aar`. The VLESS proxy feature will not compile without it.

## How to obtain libbox.aar

Option A — prebuilt (recommended):
Download `libbox-android.aar` from a prebuilt mirror such as
https://github.com/proother/sing-box-lib/releases (pick a stable sing-box
version, e.g. v1.11.x / v1.12.x), rename it to `libbox.aar`, and drop it here.

Option B — build from source:
The AAR is produced from `SagerNet/sing-box` `experimental/libbox` via gomobile:

    git clone https://github.com/SagerNet/sing-box && cd sing-box
    go run ./cmd/internal/build_libbox -target android
    # or the rnetx build script:
    #   gomobile bind -androidapi 21 -javapkg=io.nekohasekai -libname=box \
    #     -tags with_quic,with_utls,with_reality_server,with_clash_api \
    #     ./experimental/libbox

The generated Java package is `io.nekohasekai` and the native lib is
`libbox.so`, which matches the imports used by
`tw.nekomimi.nekogram.helpers.LibboxEngine`.

> If your AAR uses a different Java package (e.g. `libbox`), update the imports
> in `LibboxEngine.kt` accordingly.
