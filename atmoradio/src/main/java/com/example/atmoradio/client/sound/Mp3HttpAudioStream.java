package com.example.atmoradio.client.sound;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import net.minecraft.client.sound.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Потоковый декодер MP3 -> 16-bit PCM, без сохранения на диск.
 * HTTP-соединение открывается сразу, а декодирование кадров идёт лениво по мере
 * чтения из getStream() - то есть ровно в темпе, в котором движок звука Minecraft
 * запрашивает новые сэмплы (pull-модель), что и даёт "честный" стриминг.
 *
 * Для надёжности чтение HTTP + декодирование вынесено в отдельный поток-продюсер,
 * который кладёт готовые PCM-чанки в очередь, а PcmQueueInputStream отдаёт их движку.
 */
public class Mp3HttpAudioStream implements AudioStream {
    private static final AudioFormat FORMAT = new AudioFormat(44100f, 16, 2, true, false);

    private final PcmQueueInputStream pcmStream = new PcmQueueInputStream();
    private final Thread producerThread;
    private volatile boolean closed = false;
    private HttpURLConnection connection;

    public Mp3HttpAudioStream(String url) {
        this.producerThread = new Thread(() -> produce(url), "AtmoRadio-MP3-Decoder");
        this.producerThread.setDaemon(true);
        this.producerThread.start();
    }

    private void produce(String urlStr) {
        try {
            URL url = URI.create(urlStr).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (AtmosphericWebRadio Minecraft mod)");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.connect();

            try (InputStream httpIn = connection.getInputStream()) {
                Bitstream bitstream = new Bitstream(httpIn);
                Decoder decoder = new Decoder();

                while (!closed) {
                    Header header;
                    try {
                        header = bitstream.readFrame();
                    } catch (Exception decodeEx) {
                        // битый фрейм в потоке интернет-радио - пропускаем и едем дальше
                        break;
                    }
                    if (header == null) break; // конец потока

                    SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                    short[] pcm = output.getBuffer();
                    int len = output.getBufferLength();

                    byte[] bytes = new byte[len * 2];
                    for (int i = 0; i < len; i++) {
                        short s = pcm[i];
                        bytes[i * 2] = (byte) (s & 0xFF);
                        bytes[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
                    }
                    pcmStream.offer(bytes);

                    bitstream.closeFrame();
                }
            }
        } catch (Exception e) {
            // сетевая ошибка / 404 / обрыв соединения - просто завершаем поток,
            // RadioSoundManager поймает конец звука и подчистит состояние.
        } finally {
            pcmStream.markEnd();
        }
    }

    @Override
    public AudioFormat getFormat() {
        return FORMAT;
    }

    @Override
    public InputStream getStream() {
        return pcmStream;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        if (connection != null) connection.disconnect();
        producerThread.interrupt();
        pcmStream.close();
    }

    /** Простая блокирующая очередь байт-чанков, представленная как InputStream. */
    private static class PcmQueueInputStream extends InputStream {
        private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(64);
        private volatile boolean ended = false;
        private byte[] current;
        private int pos;

        void offer(byte[] chunk) {
            try {
                queue.put(chunk);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        void markEnd() {
            ended = true;
            try {
                queue.put(new byte[0]); // будит читателя
            } catch (InterruptedException ignored) {
            }
        }

        @Override
        public int read() {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n <= 0 ? -1 : (b[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) {
            try {
                if (current == null || pos >= current.length) {
                    current = queue.poll(2, TimeUnit.SECONDS);
                    pos = 0;
                    if (current == null) {
                        return ended ? -1 : 0; // буферизация - движок звука это терпит
                    }
                    if (current.length == 0 && ended && queue.isEmpty()) {
                        return -1;
                    }
                }
                int avail = current.length - pos;
                int toCopy = Math.min(avail, len);
                System.arraycopy(current, pos, b, off, toCopy);
                pos += toCopy;
                return toCopy;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
    }
}
