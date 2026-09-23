# Build note

The project intentionally does not include `local.properties`, because that file is machine-specific.

If Android Studio reports `SDK location not found`, open the project in Android Studio and set the Android SDK path, or create `local.properties` in the project root with your own SDK path, for example on a standard Windows installation:

sdk.dir=C:\\Users\\KOMP\\AppData\\Local\\Android\\Sdk

Then run only:

`app:assembleDebug`

The AGP messages about deprecated options are warnings, not the cause of the SDK-location failure.
