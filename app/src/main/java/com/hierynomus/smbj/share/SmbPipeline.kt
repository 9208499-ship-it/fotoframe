package com.hierynomus.smbj.share

import com.hierynomus.mssmb2.messages.SMB2ReadResponse
import java.util.concurrent.Future

/**
 * Доступ к асинхронному чтению smbj.
 *
 * Библиотека умеет отправлять запрос на чтение, не дожидаясь ответа
 * (readAsync), — именно так держат несколько запросов в полёте и не
 * упираются в задержку сети. Но метод объявлен видимым только внутри
 * пакета. Этот файл лежит в том же пакете, что и библиотека, — на Android
 * пакеты не запечатаны, и так к нему можно обратиться законно.
 *
 * Используется только потоковым чтением видео (SmbDataSource).
 */
object SmbPipeline {

    /** Сколько байт сервер отдаёт за один запрос — договорились при подключении. */
    fun maxRead(file: File): Int = file.diskShare.readBufferSize

    /** Отправить запрос на чтение и сразу вернуться; ответ — в Future. */
    fun readAsync(file: File, offset: Long, length: Int): Future<SMB2ReadResponse> =
        file.readAsync(offset, length)
}
