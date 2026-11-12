# Keep system-instantiated service entry points (referenced from the manifest,
# instantiated reflectively by the framework).
-keep class eu.woshicado.circletoshare.assist.** { *; }
-keep class eu.woshicado.circletoshare.capture.** { *; }
-keep class eu.woshicado.circletoshare.tile.** { *; }

# LiteRT / TensorFlow Lite (Copy text OCR) finds its runtime by reflection
# (org.tensorflow.lite.InterpreterFactoryImpl) and is called from native code.
-keep class org.tensorflow.lite.** { *; }
