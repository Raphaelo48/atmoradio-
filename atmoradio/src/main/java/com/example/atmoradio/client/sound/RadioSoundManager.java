package com.example.atmoradio.client.sound;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.ParticleTypes;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.openal.*;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Синглтон, который держит СОБСТВЕННЫЙ контекст OpenAL (параллельно основному движку
 * звука Minecraft) и отвечает за:
 *  - честное 3D позиционирование (слушатель = голова игрока, источник = блок радио),
 *  - линейное затухание громкости в радиусе MAX_DISTANCE,
 *  - окклюзию через EFX low-pass filter, когда между игроком и радио есть препятствие,
 *  - потоковую докачку PCM-буферов от Mp3HttpAudioStream (double/triple buffering).
 *
 * Почему отдельный контекст, а не "влезть" в SoundSystem ванильного майна:
 * ванильный пайплайн рассчитан на звуки из ресурспака (ogg/wav по Identifier) и не
 * даёт официального способа скормить произвольный живой PCM-поток. Отдельный
 * OpenAL-контекст на том же устройстве - стандартный, поддерживаемый самим OpenAL,
 * приём для таких случаев и не конфликтует со звуком игры.
 */
public final class RadioSoundManager {
    public static final RadioSoundManager INSTANCE = new RadioSoundManager();

    private static final float MAX_DISTANCE = 36.0f;   // блоков - "30-40" из ТЗ
    private static final float REFERENCE_DISTANCE = 4.0f;
    private static final int NUM_STREAM_BUFFERS = 4;
    private static final int STREAM_CHUNK_BYTES = 4096 * 4; // небольшие чанки для низкой задержки

    private long device = -1;
    private long context = -1;
    private boolean efxSupported = false;
    private long lowPassFilter = -1;

    private final Map<BlockPos, ActiveRadio> active = new ConcurrentHashMap<>();
    private Thread audioThread;
    private volatile boolean running = false;

    private RadioSoundManager() {}

    private void ensureInit() {
        if (device != -1) return;
        String defaultDeviceName = ALC10.alcGetString(0, ALC10.ALC_DEFAULT_DEVICE_SPECIFIER);
        device = ALC10.alcOpenDevice(defaultDeviceName);
        if (device == 0) {
            throw new IllegalStateException("AtmoRadio: не удалось открыть аудио-устройство для стриминга радио");
        }
        ALCCapabilities alcCaps = ALC.createCapabilities(device);
        context = ALC10.alcCreateContext(device, (int[]) null);
        ALC10.alcMakeContextCurrent(context);
        AL.createCapabilities(alcCaps);

        AL10.alDistanceModel(AL10.AL_LINEAR_DISTANCE_CLAMPED);

        efxSupported = alcCaps.ALC_EXT_EFX;
        if (efxSupported) {
            lowPassFilter = EXTEfx.alGenFilters();
            EXTEfx.alFilteri(lowPassFilter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
        }

        running = true;
        audioThread = new Thread(this::audioLoop, "AtmoRadio-Audio");
        audioThread.setDaemon(true);
        audioThread.start();
    }

    public void play(BlockPos pos, String url, String title) {
        ensureInit();
        stop(pos); // на случай смены трека
        ActiveRadio radio = new ActiveRadio(pos, url, title, efxSupported, lowPassFilter);
        active.put(pos.toImmutable(), radio);
    }

    public void stop(BlockPos pos) {
        ActiveRadio r = active.remove(pos);
        if (r != null) r.destroy();
    }

    public boolean isPlaying(BlockPos pos) {
        return active.containsKey(pos);
    }

    /** Вызывается из ClientTickEvents.END_CLIENT_TICK - лёгкие операции: частицы + raycast окклюзии. */
    public void tick(MinecraftClient client) {
        if (active.isEmpty() || client.player == null || client.world == null) return;

        Entity cam = client.getCameraEntity() != null ? client.getCameraEntity() : client.player;
        Vec3d listenerPos = cam.getCameraPosVec(1.0f);
        float yaw = cam.getYaw(1.0f);
        float pitch = cam.getPitch(1.0f);

        for (ActiveRadio radio : active.values()) {
            Vec3d sourcePos = Vec3d.ofCenter(radio.pos).add(0, 0.9, 0);
            double distance = listenerPos.distanceTo(sourcePos);

            // --- Окклюзия: простой raycast блоками между ухом игрока и радио ---
            boolean occluded = isOccluded(client.world, listenerPos, sourcePos);
            radio.setOcclusion(occluded);
            radio.updateGeometry(listenerPos, yaw, pitch, sourcePos, (float) distance);

            // --- Частицы нот, пока играет ---
            if (client.world.getTime() % 8 == 0) {
                double px = radio.pos.getX() + 0.5 + (client.world.random.nextDouble() - 0.5) * 0.4;
                double py = radio.pos.getY() + 1.05;
                double pz = radio.pos.getZ() + 0.5 + (client.world.random.nextDouble() - 0.5) * 0.4;
                client.world.addParticle(ParticleTypes.NOTE, px, py, pz,
                        client.world.random.nextDouble(), 0.0, 0.0);
            }
        }
    }

    private boolean isOccluded(ClientWorld world, Vec3d from, Vec3d to) {
        HitResult hit = world.raycast(new net.minecraft.world.RaycastContext(
                from, to,
                net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                net.minecraft.world.RaycastContext.FluidHandling.NONE,
                net.minecraft.client.MinecraftClient.getInstance().player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getPos().distanceTo(to) > 0.5;
    }

    /** Фоновый поток: единственное место, где вызывается OpenAL API (контекст должен быть "current" тут). */
    private void audioLoop() {
        ALC10.alcMakeContextCurrent(context);
        while (running) {
            for (ActiveRadio radio : active.values()) {
                radio.pump();
                if (radio.isFinished()) {
                    active.remove(radio.pos);
                    radio.destroy();
                }
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ================= ActiveRadio =================

    private static final class ActiveRadio {
        final BlockPos pos;
        final Mp3HttpAudioStream stream;
        final int alSource;
        final int[] alBuffers = new int[NUM_STREAM_BUFFERS];
        final boolean efx;
        final long lowPassFilter;
        volatile boolean occluded = false;
        volatile boolean finished = false;

        ActiveRadio(BlockPos pos, String url, String title, boolean efx, long lowPassFilter) {
            this.pos = pos.toImmutable();
            this.stream = new Mp3HttpAudioStream(url);
            this.efx = efx;
            this.lowPassFilter = lowPassFilter;

            this.alSource = AL10.alGenSources();
            AL10.alSourcef(alSource, AL10.AL_REFERENCE_DISTANCE, REFERENCE_DISTANCE);
            AL10.alSourcef(alSource, AL10.AL_MAX_DISTANCE, MAX_DISTANCE);
            AL10.alSourcef(alSource, AL10.AL_ROLLOFF_FACTOR, 1.0f);
            AL10.alSourcei(alSource, AL10.AL_LOOPING, AL10.AL_FALSE);
            AL10.alSourcef(alSource, AL10.AL_GAIN, 1.0f);

            for (int i = 0; i < NUM_STREAM_BUFFERS; i++) {
                alBuffers[i] = AL10.alGenBuffers();
            }
            // предзаполняем все буферы перед первым play, чтобы не было щелчка/тишины в начале
            int queued = 0;
            for (int buf : alBuffers) {
                if (fillBuffer(buf)) {
                    AL10.alSourceQueueBuffers(alSource, buf);
                    queued++;
                }
            }
            if (queued > 0) {
                AL10.alSourcePlay(alSource);
            }
        }

        void updateGeometry(Vec3d listenerPos, float yaw, float pitch, Vec3d sourcePos, float distance) {
            double yawRad = Math.toRadians(yaw);
            double pitchRad = Math.toRadians(pitch);
            float lookX = (float) (-Math.sin(yawRad) * Math.cos(pitchRad));
            float lookY = (float) (-Math.sin(pitchRad));
            float lookZ = (float) (Math.cos(yawRad) * Math.cos(pitchRad));

            AL10.alListener3f(AL10.AL_POSITION, (float) listenerPos.x, (float) listenerPos.y, (float) listenerPos.z);
            AL10.alListenerfv(AL10.AL_ORIENTATION, new float[]{lookX, lookY, lookZ, 0f, 1f, 0f});

            AL10.alSource3f(alSource, AL10.AL_POSITION, (float) sourcePos.x, (float) sourcePos.y, (float) sourcePos.z);

            // За пределами MAX_DISTANCE движок и так даст 0 громкости (clamped model),
            // но дополнительно приглушаем через occlusion ниже.
            float occlusionGain = occluded ? 0.35f : 1.0f;
            AL10.alSourcef(alSource, AL10.AL_GAIN, occlusionGain);

            if (efx) {
                if (occluded) {
                    EXTEfx.alFilterf(lowPassFilter, EXTEfx.AL_LOWPASS_GAIN, 1.0f);
                    EXTEfx.alFilterf(lowPassFilter, EXTEfx.AL_LOWPASS_GAINHF, 0.12f); // срезаем высокие частоты
                    AL10.alSourcei(alSource, EXTEfx.AL_DIRECT_FILTER, (int) lowPassFilter);
                } else {
                    AL10.alSourcei(alSource, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
                }
            }
        }

        void setOcclusion(boolean occluded) {
            this.occluded = occluded;
        }

        /** Докачивает освободившиеся буферы свежими PCM-данными. Вызывается из аудио-потока. */
        void pump() {
            int processed = AL10.alGetSourcei(alSource, AL10.AL_BUFFERS_PROCESSED);
            while (processed-- > 0) {
                int buf = AL10.alSourceUnqueueBuffers(alSource);
                if (fillBuffer(buf)) {
                    AL10.alSourceQueueBuffers(alSource, buf);
                } else {
                    // поток закончился/оборвался - больше нечего докачивать
                    if (AL10.alGetSourcei(alSource, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING
                            && AL10.alGetSourcei(alSource, AL10.AL_BUFFERS_QUEUED) == 0) {
                        finished = true;
                    }
                }
            }
            // если источник сам остановился (буферы опустели раньше, чем докачали) - перезапускаем
            int state = AL10.alGetSourcei(alSource, AL10.AL_SOURCE_STATE);
            if (state != AL10.AL_PLAYING && !finished && AL10.alGetSourcei(alSource, AL10.AL_BUFFERS_QUEUED) > 0) {
                AL10.alSourcePlay(alSource);
            }
        }

        private boolean fillBuffer(int bufferId) {
            try {
                byte[] chunk = new byte[STREAM_CHUNK_BYTES];
                int total = 0;
                int n;
                // стараемся набрать полный чанк, но не блокируемся навечно
                while (total < chunk.length && (n = stream.getStream().read(chunk, total, chunk.length - total)) != -1) {
                    total += n;
                    if (n == 0) break;
                }
                if (total <= 0) return false;
                ByteBuffer bb = ByteBuffer.allocateDirect(total);
                bb.put(chunk, 0, total).flip();
                AL10.alBufferData(bufferId, AL10.AL_FORMAT_STEREO16, bb, (int) stream.getFormat().getSampleRate());
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        boolean isFinished() {
            return finished;
        }

        void destroy() {
            try {
                AL10.alSourceStop(alSource);
                AL10.alSourcei(alSource, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
                AL10.alDeleteSources(alSource);
                for (int buf : alBuffers) AL10.alDeleteBuffers(buf);
                stream.close();
            } catch (Exception ignored) {
            }
        }
    }
}
