OCR models for "Copy text" (text/OcrEngine.kt).

- det960x960.tflite, det1920x960.tflite, det960x1920.tflite: PaddleOCR
  PP-OCRv5 mobile text detection (ch_PP-OCRv5_det_mobile), for pictures
  scaled to fit 960×960, 960 wide × 1920 tall, or 1920 wide × 960 tall
- rec640.tflite, rec1280.tflite: PaddleOCR PP-OCRv5 mobile Latin recognition
  (latin_PP-OCRv5_rec_mobile), for lines up to 640 and 1280 pixels wide at a
  height of 48
- latin_keys.txt: the recognizer's characters, from the model's metadata

PaddleOCR models: Copyright (c) PaddlePaddle Authors, Apache License 2.0,
https://github.com/PaddlePaddle/PaddleOCR. The ONNX exports: Copyright (c)
2021 RapidOCR Authors, Apache License 2.0, https://github.com/RapidAI/RapidOCR.
(License text: LICENSES/Apache-2.0.txt in the repository; in the app, under
Licenses.) Changed here: the ONNX exports converted to TensorFlow Lite (run by LiteRT)
with onnx2tf (float16 weights), each at a fixed input size: TensorFlow Lite
2.16 kept the output shape a free-size model was converted with, and the
recognizer's attention needs a fixed length anyway.

    onnx2tf -i ch_PP-OCRv5_det_mobile.onnx -o det960x960 -b 1 -n -ois "x:1,3,960,960"
    onnx2tf -i ch_PP-OCRv5_det_mobile.onnx -o det1920x960 -b 1 -n -ois "x:1,3,1920,960"
    onnx2tf -i ch_PP-OCRv5_det_mobile.onnx -o det960x1920 -b 1 -n -ois "x:1,3,960,1920"
    onnx2tf -i latin_PP-OCRv5_rec_mobile.onnx -o rec640 -b 1 -n -ois "x:1,3,48,640"
    onnx2tf -i latin_PP-OCRv5_rec_mobile.onnx -o rec1280 -b 1 -n -ois "x:1,3,48,1280"

(onnx2tf overwrites its input with a simplified copy: convert each size from a
fresh one. For a fixed 3-channel input it also wants
calibration_image_sample_data_20x128x128x3_float32.npy in the working
directory, any 20×128×128×3 float32 array will do. -odrqt, int8 weights,
read ~10% of the fixture lines differently: not used.)
