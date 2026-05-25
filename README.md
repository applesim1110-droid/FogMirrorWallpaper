# Fog Mirror Wallpaper

Android live wallpaper written in Kotlin with a Canvas renderer. It layers a clear image under a blurred fog image, lets the user wipe the fog with touch, adds wet edges and moving droplets, and slowly restores condensation with noisy non-uniform patches.

## Project Layout

- `app/src/main/java/com/example/fogmirror/FogMirrorWallpaperService.kt` - live wallpaper renderer and touch simulation.
- `app/src/main/java/com/example/fogmirror/MainActivity.kt` - opens the Android live wallpaper picker for this service.
- `app/src/main/res/drawable-nodpi/mirror_clear.jpg` - clear image shown underneath.
- `app/src/main/res/drawable-nodpi/fog_blur.jpg` - blurred/foggy image shown on top.
- `app/src/main/res/xml/fog_mirror_wallpaper.xml` - wallpaper metadata.

## Build

Open this folder in Android Studio and let Gradle sync. The project uses:

- Kotlin
- Android Gradle Plugin 8.7.3
- `compileSdk` / `targetSdk` 35
- `minSdk` 26

Run the app once; it immediately opens the live wallpaper picker with the `Fog Mirror` service selected.
