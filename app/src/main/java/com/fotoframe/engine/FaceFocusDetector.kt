package com.fotoframe.engine

import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Поиск лиц на снимке для эффекта наезда.
 *
 * Работает офлайн: модель зашита в APK (bundled), Google Play Services не
 * нужны — важно для ТВ-приставок, где сервисы часто урезаны.
 *
 * Детекция идёт один раз на снимок и результат сохраняется в индекс. Гонять
 * распознавание на каждом показе накладно: на слабой приставке это заметная
 * пауза. Поэтому в таблице есть поля с областью фокуса, а детектор вызывается
 * только когда они ещё не заполнены.
 *
 * Один на приложение: экран настроек и фоновый воркер создают по своему
 * PhotoEnricher, а держать две копии нативной модели в памяти незачем.
 */
object FaceFocusDetector {

    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            // Быстрый режим: нам не нужны контуры и мимика, только рамка лица.
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            // Мелкие лица в углу групповых фото для рамки не интересны,
            // порог отсекает случайные ложные срабатывания на фоне.
            .setMinFaceSize(0.12f)
            .build()
        FaceDetection.getClient(options)
    }

    /**
     * Область интереса в долях от размеров кадра (0..1) — точка, к которой
     * камера ведёт наезд.
     *
     * Одно лицо — центр по нему. Несколько — объединяющий прямоугольник, чтобы
     * в кадре остались все. Лиц нет — null, тогда показ применит обычный
     * наезд от центра.
     *
     * Сбой самого детектора (не загрузилась модель, не хватило памяти)
     * пробрасывается исключением: это не «лиц нет», и снимок должен
     * получить ещё попытку, а не пометку навсегда.
     */
    suspend fun detect(image: InputImage, width: Int, height: Int): FocusArea? {
        if (width <= 0 || height <= 0) return null

        val faces = awaitFaces(image)
        if (faces.isEmpty()) return null

        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE

        for (face in faces) {
            val box = face.boundingBox
            left = minOf(left, box.left.toFloat())
            top = minOf(top, box.top.toFloat())
            right = maxOf(right, box.right.toFloat())
            bottom = maxOf(bottom, box.bottom.toFloat())
        }

        val rect = RectF(
            (left / width).coerceIn(0f, 1f),
            (top / height).coerceIn(0f, 1f),
            (right / width).coerceIn(0f, 1f),
            (bottom / height).coerceIn(0f, 1f)
        )

        return FocusArea(
            centerX = (rect.left + rect.right) / 2f,
            centerY = (rect.top + rect.bottom) / 2f,
            faceCount = faces.size
        )
    }

    private suspend fun awaitFaces(image: InputImage): List<Face> =
        suspendCancellableCoroutine { cont ->
            detector.process(image)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        }

    /**
     * Куда вести наезд.
     *
     * @param centerX доля ширины 0..1
     * @param centerY доля высоты 0..1
     */
    data class FocusArea(
        val centerX: Float,
        val centerY: Float,
        val faceCount: Int
    )
}
