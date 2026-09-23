package dev.acoustic.mc1122.forge

import dev.acoustic.api.material.resolve.MaterialRule
import dev.acoustic.api.environment.resolve.MediumRule
import dev.acoustic.api.math.Vec3
import dev.acoustic.api.pipeline.ParallelWorkExecutor
import dev.acoustic.api.scene.AcousticScene
import dev.acoustic.api.source.AcousticSourceProfile
import dev.acoustic.core.compute.FdtdBackendRegistry
import dev.acoustic.core.compute.FdtdExternalBackend
import dev.acoustic.core.compute.GeometricBackendRegistry
import dev.acoustic.core.compute.GeometricExternalBackend
import dev.acoustic.core.passes.HybridLegacyProjector
import dev.acoustic.core.passes.HybridResponse
import dev.acoustic.core.passes.EarlyReflectionField
import dev.acoustic.core.rir.ImpulseResponse
import dev.acoustic.core.dsp.FoaImpulseResponse
import dev.acoustic.core.passes.LegacyAcousticEvaluator
import dev.acoustic.core.passes.LegacyEffectParameters
import dev.acoustic.core.passes.LegacyRoomEstimate
import dev.acoustic.core.passes.ReflectionField
import dev.acoustic.core.passes.StandardResources
import dev.acoustic.core.pipeline.MapPassContext
import dev.acoustic.core.pipeline.ParallelPipelineExecutor
import dev.acoustic.core.scene.ImmutableVoxelSnapshot
import dev.acoustic.core.source.SourceBudgetAllocator
import dev.acoustic.core.source.SourceCandidate
import dev.acoustic.core.source.profile.SourceProfileResolver
import dev.acoustic.platform.ListenerSnapshot
import dev.acoustic.platform.PlatformFrameSnapshot
import dev.acoustic.platform.PlatformFrameValidator
import dev.acoustic.platform.SoundSourceSnapshot
import dev.acoustic.mc1122.LegacyPerformanceTuning
import dev.acoustic.mc1122.LegacyPublishedState
import dev.acoustic.mc1122.LegacyRuntimeConfig
import dev.acoustic.mc1122.LegacySceneCapture
import dev.acoustic.mc1122.LegacyShaderPackRuntime
import dev.acoustic.mc1122.LegacyShaderPackFingerprint
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Main-thread capture plus coalesced worker-side acoustic analysis for Minecraft 1.12.2. */
internal class LegacyClientRuntime {
    private val configDir: Path
    private val shaderpackDirValue: Path
    private val configFile: Path
    private val gameDir: Path
    private val materialDatabase: LegacyMaterialDatabaseManager
    private val audioConfigFile: Path
    @Volatile private var audioConfig: LegacyAudioConfig
    private val wetRenderer: LegacySoftwareWetRenderer
    private val deferredWetResults = ConcurrentLinkedQueue<LegacySoftwareWetRenderer.Result>()
    private var audioConfigStamp = 0L
    @Volatile private var listenerForwardValue = Vec3(0.0, 0.0, 1.0)

    @Volatile private var materialRules: List<MaterialRule> = emptyList()
    @Volatile private var mediumRules: List<MediumRule> = emptyList()
    @Volatile private var sourceProfiles = SourceProfileResolver(emptyList(), AcousticSourceProfile.GENERIC)
    @Volatile private lateinit var configValue: LegacyRuntimeConfig
    @Volatile private lateinit var packValue: LegacyShaderPackRuntime
    private lateinit var capture: LegacySceneCapture

    private val evaluator = LegacyAcousticEvaluator()
    private val fullProjector = HybridLegacyProjector()
    private val sourceBudget = SourceBudgetAllocator()
    private val projectileEmitters = LegacyProjectileEmitterManager()
    @Volatile private var publishedValue: LegacyPublishedState = LegacyPublishedState.EMPTY

    private var lastWorld: Any? = null
    private var lastListener: Vec3? = null
    private var ticks = 0
    private var lastFullRefreshTick = 0
    private var epoch = 1L
    private var configStamp = 0L
    @Volatile private var shaderPackObservedFingerprint = ""
    @Volatile private var shaderPackActiveFingerprint = ""

    private lateinit var analysisExecutor: ExecutorService
    private lateinit var physicsWorkers: ExecutorService
    private lateinit var parallelWork: ParallelWorkExecutor
    private lateinit var pipelineExecutor: ParallelPipelineExecutor
    @Volatile private var pendingRoom: RoomRequest? = null
    private var roomWorkerRunning = false

    private val sourceLock = Any()
    private val activeSources = LinkedHashMap<Int, SourceState>()
    private val pendingSources = LinkedHashMap<Int, SourceRequest>()
    private val completedSources = ConcurrentLinkedQueue<SourceResult>()
    private var sourceGeneration = 0L
    private var sourceWorkerRunning = false
    @Volatile private var sourceFrameRefreshRevision = 0L
    @Volatile private var scheduledSourceFrameRefreshRevision = 0L

    @Volatile private var fullSourceSolved = 0L
    @Volatile private var fullSourceStale = 0L
    @Volatile private var fullSourceLagAccepted = 0L
    @Volatile private var lastFullSourceMs = 0.0
    @Volatile private var lastWaveBackend = "none"
    @Volatile private var validatedPlatformFrames = 0L
    @Volatile private var lastPlatformFrameEpoch = 0L
    @Volatile private var lastPlatformFrameSequence = 0L
    @Volatile private var lastPlatformFrameSources = 0
    @Volatile private var lastPlatformFrameMovingSources = 0

    @Throws(IOException::class)
    constructor(configDir: Path) : this(configDir, configDir.parent ?: configDir)

    @Throws(IOException::class)
    constructor(configDir: Path, gameDir: Path) {
        this.configDir = configDir
        this.gameDir = gameDir
        shaderpackDirValue = configDir.resolve("shaderpacks")
        configFile = configDir.resolve("runtime.properties")
        Files.createDirectories(shaderpackDirValue)
        materialDatabase = LegacyMaterialDatabaseManager(gameDir)
        audioConfigFile = configDir.resolve("legacy-audio.properties")
        audioConfig = LegacyAudioConfig.loadOrCreate(audioConfigFile)
        wetRenderer = LegacySoftwareWetRenderer(audioConfig)
        audioConfigStamp = if (Files.isRegularFile(audioConfigFile)) Files.getLastModifiedTime(audioConfigFile).toMillis() else 0L
        materialDatabase.ensureSkeleton()
        val snapshot = materialDatabase.loadCached()
        materialRules = snapshot.rules
        mediumRules = snapshot.mediumRules
        sourceProfiles = snapshot.sourceProfiles
        reload()
        LegacySoundHook.bind(this)
    }

    @Synchronized
    fun refreshGeneratedMaterialDatabase() {
        try {
            val snapshot = materialDatabase.initializeOrRefreshGenerated()
            materialRules = snapshot.rules
            mediumRules = snapshot.mediumRules
            sourceProfiles = snapshot.sourceProfiles
            val next = LegacyRuntimeConfig.loadOrCreate(configFile)
            val fingerprint = LegacyShaderPackFingerprint.compute(shaderpackDirValue, next.packs())
            val nextPack = LegacyShaderPackRuntime.load(shaderpackDirValue, next, materialRules, mediumRules)
            activate(next, nextPack, fingerprint)
        } catch (t: Throwable) {
            AcousticLog.error("generated material database refresh failed", t)
        }
    }

    fun tick() {
        ticks++
        try {
            if (ticks % 40 == 0) {
                reloadIfChanged()
                reloadShaderPacksIfChanged()
                reloadAudioIfChanged()
                reloadMaterialResourcesIfChanged()
            }
            if (configValue.debug() && ticks % 200 == 0) AcousticLog.debug("compute diagnostics: ${computeDiagnostics()}")
            if (packValue.disabled() || !configValue.effectsEnabled()) {
                publishedValue = LegacyPublishedState.EMPTY
                lastListener = null
                return
            }

            val mc = requireNotNull(ForgeReflection.invoke(
                ForgeReflection.type("net.minecraft.client.Minecraft"),
                arrayOf("getMinecraft", "func_71410_x")
            ))
            val world = try {
                ForgeReflection.field(mc, "world", "field_71441_e")
            } catch (_: RuntimeException) {
                null
            }
            var entity: Any? = null
            if (world != null) {
                entity = try {
                    ForgeReflection.invoke(mc, arrayOf("getRenderViewEntity", "func_175606_aa"))
                } catch (_: RuntimeException) {
                    try { ForgeReflection.field(mc, "player", "field_71439_g") } catch (_: RuntimeException) { null }
                }
            }
            if (world == null || entity == null) {
                if (lastWorld != null) {
                    lastWorld = null
                    lastListener = null
                    capture.reset()
                    projectileEmitters.clear()
                    LegacyDirectPathDiagnostic.clear()
                    pendingRoom = null
                    publishedValue = LegacyPublishedState.EMPTY
                    epoch++
                }
                return
            }

            val listener = listener(entity)
            projectileEmitters.tick(world, entity)
            listenerForwardValue = listenerForward(entity)
            val changedWorld = world !== lastWorld
            val previousListener = lastListener
            val moved = previousListener == null || listener.distance(previousListener) >= 1.0
            if (changedWorld) {
                lastWorld = world
                lastListener = null
                capture.reset()
                projectileEmitters.clear()
                LegacyDirectPathDiagnostic.clear()
                pendingRoom = null
                publishedValue = LegacyPublishedState.EMPTY
                epoch++
                lastFullRefreshTick = ticks
            }

            val performance = packValue.performanceTuning()
            val bootstrapPending = capture.bootstrapActive()
            val sourceFrameRefreshPending = sourceFrameRefreshRevision != scheduledSourceFrameRefreshRevision
            if (!moved && !changedWorld && !bootstrapPending && !sourceFrameRefreshPending && ticks % performance.captureIntervalTicks() != 0) return

            val horizontal = performance.horizontalRadius()
            val vertical = performance.verticalRadius()
            val centerX = floor(listener.x).toInt()
            val centerY = floor(listener.y).toInt()
            val centerZ = floor(listener.z).toInt()
            val beginBootstrap = changedWorld
            val startSweep = !beginBootstrap && !bootstrapPending && ticks - lastFullRefreshTick >= performance.fullRefreshTicks()
            if (startSweep) lastFullRefreshTick = ticks
            val sizeX = horizontal * 2 + 1
            val sizeY = vertical * 2 + 1
            val sizeZ = horizontal * 2 + 1
            val volume = sizeX * sizeY * sizeZ
            val sweepBudget = max(
                256,
                ceil(volume.toDouble() * max(1, performance.captureIntervalTicks()) / max(1, performance.fullRefreshTicks())).toInt()
            )
            val access = ReflectiveWorldAccess(world, epoch * 1_000_000L + ticks)
            val captureStarted = System.nanoTime()
            val scene: ImmutableVoxelSnapshot = capture.captureRollingProgressive(
                access,
                centerX - horizontal,
                centerY - vertical,
                centerZ - horizontal,
                sizeX,
                sizeY,
                sizeZ,
                centerX,
                centerY,
                centerZ,
                performance.refreshRadius(),
                beginBootstrap,
                performance.captureBudgetMillis(),
                startSweep,
                sweepBudget
            )
            val captureMs = (System.nanoTime() - captureStarted) / 1_000_000.0
            val capturedSources = captureSourceSeeds()
            val frameSeeds = capturedSources.seeds
            val platformFrame = PlatformFrameSnapshot(
                scene,
                ListenerSnapshot(listener, listenerForwardValue, Vec3(0.0, 1.0, 0.0)),
                frameSeeds.map { seed ->
                    SoundSourceSnapshot(
                        seed.sourceId.toLong(),
                        seed.platformSoundId(),
                        seed.position,
                        seed.gain.toFloat(),
                        seed.profile,
                        seed.velocity
                    )
                },
                epoch,
                ticks.toLong()
            )
            PlatformFrameValidator.requireValid(platformFrame)
            validatedPlatformFrames++
            lastPlatformFrameEpoch = platformFrame.worldEpoch()
            lastPlatformFrameSequence = platformFrame.frameSequence()
            lastPlatformFrameSources = platformFrame.sources().size
            lastPlatformFrameMovingSources = platformFrame.sources().count { it.velocity().lengthSquared() > 1.0e-12 }
            if ((beginBootstrap || bootstrapPending) && !capture.bootstrapActive()) lastFullRefreshTick = ticks
            val geometryChanged = capture.lastChangedVoxels() > 0
            val capturedSourceRefreshPending = capturedSources.refreshRevision != scheduledSourceFrameRefreshRevision
            if (!changedWorld && !moved && !geometryChanged && !capturedSourceRefreshPending) {
                lastListener = listener
                if (configValue.debug()) {
                    AcousticLog.debug(
                        "capture ${scene.sizeX()}x${scene.sizeY()}x${scene.sizeZ()} sampled=${capture.lastSampledVoxels()} reused=${capture.lastReusedVoxels()} changed=0 " +
                            "bootstrap=${capture.lastBootstrapSampledVoxels()} bootstrapProgress=${capture.bootstrapProgress()} sweep=${capture.lastSweepSampledVoxels()} " +
                            "sweepActive=${capture.sweepActive()} ms=$captureMs budgetMs=${performance.captureBudgetMillis()} workers=${performance.workers()} unchanged=true"
                    )
                }
                return
            }

            scheduleRoom(RoomRequest(platformFrame, frameSeeds, configValue.effectsEnabled(), packValue, performance, parallelWork))
            if (capturedSourceRefreshPending) {
                synchronized(sourceLock) {
                    if (capturedSources.refreshRevision > scheduledSourceFrameRefreshRevision) {
                        scheduledSourceFrameRefreshRevision = capturedSources.refreshRevision
                    }
                }
            }
            lastListener = listener
            if (configValue.debug()) {
                AcousticLog.debug(
                    "capture ${scene.sizeX()}x${scene.sizeY()}x${scene.sizeZ()} sampled=${capture.lastSampledVoxels()} reused=${capture.lastReusedVoxels()} " +
                        "changed=${capture.lastChangedVoxels()} bootstrapStart=$beginBootstrap bootstrap=${capture.lastBootstrapSampledVoxels()} " +
                        "bootstrapProgress=${capture.bootstrapProgress()} sweep=${capture.lastSweepSampledVoxels()} sweepActive=${capture.sweepActive()} ms=$captureMs " +
                        "budgetMs=${performance.captureBudgetMillis()} workers=${performance.workers()}"
                )
            }
        } catch (t: Throwable) {
            debugError("client tick", t)
        }
    }

    private fun scheduleRoom(request: RoomRequest) {
        synchronized(this) {
            pendingRoom = request
            if (roomWorkerRunning) return
            roomWorkerRunning = true
        }
        analysisExecutor.submit { roomWorkerLoop() }
    }

    private fun roomWorkerLoop() {
        while (true) {
            val request: RoomRequest = synchronized(this) {
                val next = pendingRoom
                pendingRoom = null
                if (next == null) {
                    roomWorkerRunning = false
                    return
                }
                next
            }
            try {
                val room = evaluator.estimateRoomParallel(
                    request.frame.scene(),
                    request.frame.listener().position(),
                    request.performance.roomProbeDistance(),
                    request.pack.liveTuning(),
                    request.performance.roomRays(),
                    request.parallel
                )
                if (epoch != request.frame.worldEpoch()) continue
                publishedValue = LegacyPublishedState(request.frame.scene(), request.frame.listener().position(), room, null, request.frame.worldEpoch(), request.effectsEnabled)
                if (debug()) AcousticLog.debug("room async mean=${room.meanFreePathMeters()} openness=${room.openness()} rt60=${room.decayTimeSeconds()} density=${room.density()} hf=${room.gainHf()}")
                val reflection = computeListenerInvariant(request)
                if (epoch == request.frame.worldEpoch()) {
                    publishedValue = LegacyPublishedState(request.frame.scene(), request.frame.listener().position(), room, reflection, request.frame.worldEpoch(), request.effectsEnabled)
                    scheduleActiveSources(request.frame, request.sourceSeeds, reflection, request.pack)
                    if (debug()) AcousticLog.debug("shared listener reflections ready samples=${reflection?.samples()?.size ?: 0}")
                }
            } catch (t: Throwable) {
                debugError("room worker", t)
            }
        }
    }

    @Throws(Exception::class)
    private fun computeListenerInvariant(request: RoomRequest): ReflectionField? {
        if (request.pack.listenerInvariantPipeline().passes().isEmpty()) return null
        val context = MapPassContext()
        context.put(StandardResources.SCENE, request.frame.scene())
        context.put(StandardResources.LISTENER_POSITION, request.frame.listener().position())
        pipelineExecutor.executeProfiled(request.pack.listenerInvariantPipeline(), context)
        return context.get(StandardResources.REFLECTION_FIELD)
    }

    fun sourceStarted(sourceId: Int, soundId: String?, position: Vec3?, gain: Double, importance: Double, streaming: Boolean, looping: Boolean): Long {
        if (sourceId <= 0 || position == null) return 0L
        val profile = sourceProfiles.resolve(soundId)
        val generation: Long
        synchronized(sourceLock) {
            generation = ++sourceGeneration
            activeSources[sourceId] = SourceState(generation, soundId, profile, position, gain, importance, streaming, looping)
        }
        LegacyDirectPathDiagnostic.arm(sourceId, generation)
        val state = publishedValue
        val acousticsActive = configValue.effectsEnabled() && !packValue.disabled() && !effectiveSourceProfile(profile, packValue).bypassAcoustics()
        if (acousticsActive && fullSourceReady(state, packValue)) {
            queueSource(sourceId, generation, position, requireNotNull(state.scene()), requireNotNull(state.listener()), state.reflectionField(), state.epoch(), packValue)
        } else if (acousticsActive) {
            synchronized(sourceLock) {
                if (activeSources[sourceId]?.generation == generation) sourceFrameRefreshRevision++
            }
        }
        return generation
    }

    fun sourceProfileFor(soundId: String?): AcousticSourceProfile = effectiveSourceProfile(sourceProfiles.resolve(soundId), packValue)

    fun sourceMoved(sourceId: Int, position: Vec3?) {
        if (sourceId <= 0 || position == null) return
        val published = publishedValue
        var state: SourceState? = null
        var shouldQueue = false
        val now = System.nanoTime()
        synchronized(sourceLock) {
            state = activeSources[sourceId]
            val active = state
            if (active != null) {
                val old = active.position
                val dt = now - active.lastMoveNanos
                active.position = position
                if (dt > 1_000_000L) {
                    val seconds = dt / 1_000_000_000.0
                    active.velocity = clampVelocity(Vec3((position.x - old.x) / seconds, (position.y - old.y) / seconds, (position.z - old.z) / seconds))
                }
                active.lastMoveNanos = now
                val effective = effectiveSourceProfile(active.profile, packValue)
                if (!effective.bypassAcoustics() && fullSourceReady(published, packValue)) {
                    val threshold = packValue.performanceTuning().sourceMoveThreshold() / max(0.1, effective.movementSensitivity().toDouble())
                    val scene = published.scene()
                    val listener = published.listener()
                    if (scene != null && listener != null) {
                        val queuedPosition = active.lastQueuedPosition
                        val queuedListener = active.lastQueuedListener
                        shouldQueue = queuedPosition == null || queuedListener == null ||
                            active.lastQueuedSceneRevision != scene.revision() || active.lastQueuedEpoch != published.epoch() ||
                            position.distance(queuedPosition) >= threshold || listener.distance(queuedListener) >= threshold
                    }
                }
            }
        }
        val active = state
        val scene = published.scene()
        val listener = published.listener()
        if (active != null && shouldQueue && scene != null && listener != null) {
            queueSource(sourceId, active.generation, position, scene, listener, published.reflectionField(), published.epoch(), packValue)
        }
    }

    fun sourceStopped(sourceId: Int) {
        synchronized(sourceLock) {
            activeSources.remove(sourceId)
            pendingSources.remove(sourceId)
        }
        LegacyDirectPathDiagnostic.disarm(sourceId)
        wetRenderer.invalidate(sourceId)
    }

    fun sourcePcmCaptured(sourceId: Int, generation: Long, capture: LegacySoftwareWetRenderer.PcmCapture?) {
        if (capture == null) return
        synchronized(sourceLock) {
            val state = activeSources[sourceId] ?: return
            if (state.generation == generation && !state.streaming && !state.looping) state.pcmCapture = capture
        }
    }

    private fun captureSourceSeeds(): CapturedSourceSeeds {
        val seeds = ArrayList<SourceRequestSeed>()
        synchronized(sourceLock) {
            for ((sourceId, state) in activeSources) {
                seeds.add(
                    SourceRequestSeed(
                        sourceId,
                        state.generation,
                        state.position,
                        state.soundId,
                        state.profile,
                        state.velocity,
                        state.gain,
                        state.importance
                    )
                )
            }
            return CapturedSourceSeeds(sourceFrameRefreshRevision, seeds)
        }
    }

    private fun scheduleActiveSources(frame: PlatformFrameSnapshot, sourceSeeds: List<SourceRequestSeed>, reflection: ReflectionField?, requestPack: LegacyShaderPackRuntime) {
        val candidates = ArrayList<SourceCandidate>()
        val seeds = LinkedHashMap<Long, SourceRequestSeed>()
        for (seed in sourceSeeds) {
            val id = seed.sourceId.toLong()
            val effective = effectiveSourceProfile(seed.profile, requestPack)
            if (effective.bypassAcoustics()) continue
            candidates.add(
                SourceCandidate(
                    id,
                    seed.position,
                    seed.gain,
                    seed.importance * effective.priorityScale().toDouble() * sqrt(max(0.1, effective.transientScale().toDouble()))
                )
            )
            seeds[id] = seed
        }
        val listener = frame.listener().position()
        val slots = requestPack.performanceTuning().liveSourceLimit()
        val allocation = sourceBudget.allocate(candidates, listener, slots, 0)
        for (candidate in allocation.high()) {
            val seed = seeds[candidate.id()] ?: continue
            queueSource(seed.sourceId, seed.generation, seed.position, frame.scene(), listener, reflection, frame.worldEpoch(), requestPack, seed)
        }
    }

    private fun queueSource(
        sourceId: Int,
        generation: Long,
        position: Vec3,
        scene: AcousticScene,
        listener: Vec3,
        reflection: ReflectionField?,
        requestEpoch: Long,
        requestPack: LegacyShaderPackRuntime,
        capturedSeed: SourceRequestSeed? = null
    ) {
        var start = false
        synchronized(sourceLock) {
            val active = activeSources[sourceId] ?: return
            if (active.generation != generation) return
            val hasReflection = reflection != null
            val queuedPosition = active.lastQueuedPosition
            val queuedListener = active.lastQueuedListener
            if (queuedPosition != null && queuedListener != null &&
                active.lastQueuedEpoch == requestEpoch && active.lastQueuedSceneRevision == scene.revision() &&
                position.distance(queuedPosition) < 1.0e-6 && listener.distance(queuedListener) < 1.0e-6 &&
                (active.lastQueuedHadReflection || !hasReflection)
            ) return
            active.lastQueuedPosition = position
            active.lastQueuedListener = listener
            active.lastQueuedSceneRevision = scene.revision()
            active.lastQueuedEpoch = requestEpoch
            active.lastQueuedHadReflection = hasReflection
            val requestSeed = capturedSeed ?: SourceRequestSeed(
                sourceId,
                active.generation,
                position,
                active.soundId,
                active.profile,
                active.velocity,
                active.gain,
                active.importance
            )
            pendingSources[sourceId] = SourceRequest(
                sourceId,
                generation,
                position,
                requestSeed.soundId,
                requestSeed.profile,
                requestSeed.velocity,
                scene,
                listener,
                reflection,
                requestEpoch,
                requestPack
            )
            if (!sourceWorkerRunning) {
                sourceWorkerRunning = true
                start = true
            }
        }
        if (start) analysisExecutor.submit { sourceWorkerLoop() }
    }

    private fun sourceWorkerLoop() {
        while (true) {
            val request: SourceRequest = synchronized(sourceLock) {
                if (pendingSources.isEmpty()) {
                    sourceWorkerRunning = false
                    return
                }
                val first = pendingSources.keys.iterator().next()
                requireNotNull(pendingSources.remove(first))
            }
            val start = System.nanoTime()
            try {
                val context = MapPassContext()
                context.put(StandardResources.SCENE, request.scene)
                context.put(StandardResources.SOURCE_POSITION, request.position)
                context.put(StandardResources.SOURCE_ID, request.soundId)
                context.put(StandardResources.SOURCE_PROFILE, request.profile)
                context.put(StandardResources.LISTENER_POSITION, request.listener)
                if (request.reflection != null) context.put(StandardResources.REFLECTION_FIELD, request.reflection)
                val report = pipelineExecutor.executeProfiled(request.pack.sourcePipeline(), context)
                val response: HybridResponse = context.require(StandardResources.HYBRID_RESPONSE)
                val rir: ImpulseResponse? = context.get(StandardResources.IMPULSE_RESPONSE)
                val early: EarlyReflectionField? = context.get(StandardResources.EARLY_REFLECTIONS)
                val foa: FoaImpulseResponse? = context.get(StandardResources.FOA_IMPULSE_RESPONSE)
                val behavior = context.get(StandardResources.SOURCE_BEHAVIOR) ?: AcousticSourceProfile.GENERIC
                val effect = fullProjector.project(response, request.pack.liveTuning(), behavior)
                val backend = response.wave()?.backendId() ?: "none"
                val millis = (System.nanoTime() - start) / 1_000_000.0
                completedSources.add(
                    SourceResult(
                        request.sourceId,
                        request.generation,
                        request.scene.revision(),
                        request.epoch,
                        request.position,
                        request.listener,
                        request.soundId,
                        behavior,
                        request.velocity,
                        effect,
                        response.direct(),
                        rir,
                        early,
                        foa,
                        backend,
                        millis,
                        report.elapsedNanos()
                    )
                )
                fullSourceSolved++
                lastFullSourceMs = millis
                lastWaveBackend = backend
                LegacySoundHook.wakeAudioThread()
                if (debug()) {
                    AcousticLog.debug("full shader source=${request.sourceId} ms=$millis wave=$backend")
                    if (behavior.category().equals("explosion", ignoreCase = true)) {
                        val direct = response.direct()
                        if (direct != null) {
                            AcousticLog.debug(
                                "explosion path source=${request.sourceId} cells=${direct.solidCells} thickness=${direct.occupiedMeters}m low=${direct.transmission[0]} " +
                                    "high=${direct.transmission[direct.transmission.size - 1]} diffraction=${response.diffraction()?.available() == true}"
                            )
                        }
                    }
                    if (millis >= 25.0) AcousticLog.debug("slow shader source=${request.sourceId} ms=$millis passes=${report.timings()}")
                }
            } catch (t: Throwable) {
                if (t !is InterruptedException) debugError("full shader source", t)
            }
        }
    }

    fun drainFullSourceResults(efx: LegacyEfxBackend, wetBackend: LegacySoftwareWetBackend) {
        if (!audioConfig.softwareWetEnabled) {
            for (sourceId in wetBackend.clearAll()) restoreEfx(sourceId, efx)
        }
        drainWetResults(efx, wetBackend)
        var drained = 0
        while (drained < 32) {
            val result = completedSources.poll() ?: break
            drained++
            val state = publishedValue
            val active = synchronized(sourceLock) { activeSources[result.sourceId] }
            if (active == null || active.generation != result.generation || !state.ready() || state.epoch() != result.epoch) {
                fullSourceStale++
                continue
            }
            val scene = state.scene()
            val listener = state.listener()
            if (scene == null || listener == null) {
                fullSourceStale++
                continue
            }
            val currentRevision = scene.revision()
            val lag = currentRevision - result.sceneRevision
            val exact = lag == 0L
            val performance = packValue.performanceTuning()
            val nearEnough = listener.distance(result.listener) <= 1.0 &&
                active.position.distance(result.sourcePosition) <= max(0.2, performance.sourceMoveThreshold() * 2.0)
            val boundedLag = lag > 0L && lag <= max(2, performance.captureIntervalTicks() * 2).toLong() && nearEnough
            if (!exact && !boundedLag) {
                fullSourceStale++
                continue
            }
            if (boundedLag) fullSourceLagAccepted++
            LegacyDirectPathDiagnostic.publish(result.sourceId, result.generation, result.direct)
            active.lastEffect = result.effect
            active.lastEffectRevision = currentRevision
            val capture = active.pcmCapture
            val canSoftwareWet = audioConfig.softwareWetEnabled && capture != null && result.rir != null && !active.streaming && !active.looping
            if (canSoftwareWet && wetBackend.isActive(result.sourceId, result.generation)) efx.applyDirectOnly(result.sourceId, result.effect)
            else efx.apply(result.sourceId, result.effect, state.room(), currentRevision)
            efx.applyVelocity(result.sourceId, result.velocity, result.behavior.dopplerScale())
            if (canSoftwareWet) {
                wetRenderer.submit(LegacySoftwareWetRenderer.Request(
                    result.sourceId, result.generation, result.epoch, result.sceneRevision, requireNotNull(capture), requireNotNull(result.rir), result.early, result.foa, listenerForwardValue
                ))
            }
        }
    }

    private fun drainWetResults(efx: LegacyEfxBackend, wetBackend: LegacySoftwareWetBackend) {
        val initial = min(32, deferredWetResults.size + 32)
        var drained = 0
        while (drained < initial) {
            val result = deferredWetResults.poll() ?: wetRenderer.poll() ?: break
            drained++
            val published = publishedValue
            val active = synchronized(sourceLock) { activeSources[result.sourceId] }
            if (active == null || active.generation != result.generation || !published.ready() || published.epoch() != result.epoch) {
                wetBackend.stale++
                continue
            }
            val scene = published.scene()
            if (scene == null || scene.revision() < result.sceneRevision) { wetBackend.stale++; continue }
            val applied = wetBackend.apply(result.sourceId, result.generation, result.rendered)
            if (applied.paused) { deferredWetResults.add(result); continue }
            if (applied.applied) {
                val effect = active.lastEffect
                if (effect != null) efx.applyDirectOnly(result.sourceId, effect)
                if (applied.evictedDrySource > 0) restoreEfx(applied.evictedDrySource, efx)
            }
        }
    }

    private fun restoreEfx(sourceId: Int, efx: LegacyEfxBackend) {
        val active = synchronized(sourceLock) { activeSources[sourceId] } ?: return
        val effect = active.lastEffect ?: return
        val state = publishedValue
        val scene = state.scene() ?: return
        if (state.ready()) efx.apply(sourceId, effect, state.room(), scene.revision())
    }

    fun computeDiagnostics(): String =
        "fullSolved=$fullSourceSolved stale=$fullSourceStale lagAccepted=$fullSourceLagAccepted lastMs=$lastFullSourceMs lastWave=$lastWaveBackend " +
            "platformFrame={validated=$validatedPlatformFrames epoch=$lastPlatformFrameEpoch sequence=$lastPlatformFrameSequence sources=$lastPlatformFrameSources} " +
            "platformMotion={movingSources=$lastPlatformFrameMovingSources} " +
            "sourceFrameRefresh={requested=$sourceFrameRefreshRevision scheduled=$scheduledSourceFrameRefreshRevision} " +
            "shaderStack={active=${shaderPackActiveFingerprint.take(12)} observed=${shaderPackObservedFingerprint.take(12)}} " +
            "softwareWet={enabled=${audioConfig.softwareWetEnabled} submitted=${wetRenderer.submitted} rendered=${wetRenderer.rendered} dropped=${wetRenderer.dropped} failures=${wetRenderer.failures}} " +
            "rayCompute={${rayBackendDiagnostics()}} waveCompute={${waveBackendDiagnostics()}}"

    fun published(): LegacyPublishedState = publishedValue
    fun debug(): Boolean = ::configValue.isInitialized && configValue.debug()
    fun effectsActive(): Boolean = ::configValue.isInitialized && configValue.effectsEnabled() && ::packValue.isInitialized && !packValue.disabled()
    fun captureBootstrapActive(): Boolean = ::capture.isInitialized && capture.bootstrapActive()
    fun debugError(where: String, t: Throwable) { if (debug()) AcousticLog.error("$where failed", t) }
    fun config(): LegacyRuntimeConfig = configValue
    fun legacyAudioConfig(): LegacyAudioConfig = audioConfig

    @Synchronized
    fun applyLegacyAudioConfig(next: LegacyAudioConfig) {
        next.save(audioConfigFile)
        audioConfig = next
        audioConfigStamp = if (Files.isRegularFile(audioConfigFile)) Files.getLastModifiedTime(audioConfigFile).toMillis() else 0L
        wetRenderer.reconfigure(next)
        if (!next.softwareWetEnabled) { wetRenderer.clear(); deferredWetResults.clear() }
        AcousticLog.info("software wet convolution ${if (next.softwareWetEnabled) "enabled" else "disabled"}; threads=${next.rendererThreads} voices=${next.maxWetVoices}")
    }
    fun pack(): LegacyShaderPackRuntime = packValue
    fun shaderpackDir(): Path = shaderpackDirValue

    @Synchronized
    @Throws(IOException::class)
    fun applyUiConfiguration(stack: List<String>, profile: String, overrides: Map<String, String>) {
        val next = configValue.withUi(stack, profile, overrides)
        val fingerprint = LegacyShaderPackFingerprint.compute(shaderpackDirValue, next.packs())
        val nextPack = LegacyShaderPackRuntime.load(shaderpackDirValue, next, materialRules, mediumRules)
        next.save(configFile)
        activate(next, nextPack, fingerprint)
    }

    private fun reloadIfChanged() {
        try {
            val stamp = if (Files.isRegularFile(configFile)) Files.getLastModifiedTime(configFile).toMillis() else 0L
            if (stamp != configStamp) reload()
        } catch (t: Throwable) {
            debugError("config reload", t)
        }
    }

    private fun reloadAudioIfChanged() {
        try {
            val stamp = if (Files.isRegularFile(audioConfigFile)) Files.getLastModifiedTime(audioConfigFile).toMillis() else 0L
            if (stamp == audioConfigStamp) return
            val next = LegacyAudioConfig.loadOrCreate(audioConfigFile)
            audioConfig = next; audioConfigStamp = stamp; wetRenderer.reconfigure(next)
            AcousticLog.info("software wet convolution ${if (next.softwareWetEnabled) "enabled" else "disabled"}; threads=${next.rendererThreads} voices=${next.maxWetVoices}")
        } catch (t: Throwable) { debugError("legacy audio config reload", t) }
    }

    @Throws(IOException::class)
    private fun reload() {
        val next = LegacyRuntimeConfig.loadOrCreate(configFile)
        val fingerprint = LegacyShaderPackFingerprint.compute(shaderpackDirValue, next.packs())
        val nextPack = LegacyShaderPackRuntime.load(shaderpackDirValue, next, materialRules, mediumRules)
        activate(next, nextPack, fingerprint)
    }

    /**
     * Content-based, transactional shader-pack hot reload. Invalid edits never replace the last
     * working runtime; a later content change retriggers validation automatically.
     */
    private fun reloadShaderPacksIfChanged() {
        try {
            val fingerprint = LegacyShaderPackFingerprint.compute(shaderpackDirValue, configValue.packs())
            if (fingerprint == shaderPackObservedFingerprint) return
            shaderPackObservedFingerprint = fingerprint
            val nextPack = LegacyShaderPackRuntime.load(shaderpackDirValue, configValue, materialRules, mediumRules)
            activate(configValue, nextPack, fingerprint)
            AcousticLog.info("acoustic shader stack hot-reloaded: ${nextPack.stackNames()}")
        } catch (t: Throwable) {
            // Keep the already-active runtime. observedFingerprint remains on the rejected bytes so
            // we do not spam retries every poll; editing the pack again produces a new fingerprint.
            debugError("shader pack hot reload", t)
        }
    }

    private fun reloadMaterialResourcesIfChanged() {
        try {
            val snapshot = materialDatabase.pollResourcePackChanges() ?: return
            materialRules = snapshot.rules
            mediumRules = snapshot.mediumRules
            sourceProfiles = snapshot.sourceProfiles
            val fingerprint = LegacyShaderPackFingerprint.compute(shaderpackDirValue, configValue.packs())
            val nextPack = LegacyShaderPackRuntime.load(shaderpackDirValue, configValue, materialRules, mediumRules)
            activate(configValue, nextPack, fingerprint)
        } catch (t: Throwable) {
            debugError("material resource reload", t)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    private fun activate(next: LegacyRuntimeConfig, nextPack: LegacyShaderPackRuntime, shaderFingerprint: String = LegacyShaderPackFingerprint.compute(shaderpackDirValue, next.packs())) {
        val oldAnalysis = if (::analysisExecutor.isInitialized) analysisExecutor else null
        val oldPhysics = if (::physicsWorkers.isInitialized) physicsWorkers else null
        val oldPipeline = if (::pipelineExecutor.isInitialized) pipelineExecutor else null
        val workerCount = nextPack.performanceTuning().workers()
        physicsWorkers = newWorkers(workerCount, "acoustic-physics-worker-")
        pipelineExecutor = ParallelPipelineExecutor(physicsWorkers, workerCount)
        parallelWork = pipelineExecutor.parallelWork()
        analysisExecutor = newWorkers(if (Runtime.getRuntime().availableProcessors() >= 8) 2 else 1, "acoustic-analysis-")
        oldPipeline?.close()
        oldAnalysis?.shutdownNow()
        oldPhysics?.shutdownNow()
        pendingRoom = null
        roomWorkerRunning = false
        synchronized(sourceLock) {
            pendingSources.clear()
            sourceWorkerRunning = false
        }
        completedSources.clear()
        deferredWetResults.clear()
        wetRenderer.clear()
        configValue = next
        packValue = nextPack
        capture = LegacySceneCapture(nextPack.resolver(), nextPack.mediumResolver())
        configStamp = if (Files.isRegularFile(configFile)) Files.getLastModifiedTime(configFile).toMillis() else 0L
        shaderPackObservedFingerprint = shaderFingerprint
        shaderPackActiveFingerprint = shaderFingerprint
        epoch++
        publishedValue = LegacyPublishedState.EMPTY
        lastListener = null
        lastFullRefreshTick = ticks
        AcousticLog.setDebug(next.debug())
        AcousticLog.info("shader=${if (nextPack.disabled()) "None" else nextPack.stackNames()} preset=${nextPack.profile()} cpuWorkers=${nextPack.performanceTuning().workers()}")
        AcousticLog.debug(
            "active shader stack: ${nextPack.stackNames()} preset=${nextPack.profile()} options=${next.optionOverrides()} cpuWorkers=${nextPack.performanceTuning().workers()} " +
                "liveSources=${nextPack.performanceTuning().liveSourceLimit()} moveThreshold=${nextPack.performanceTuning().sourceMoveThreshold()} " +
                "captureBudgetMs=${nextPack.performanceTuning().captureBudgetMillis()} materialRules=${materialRules.size} mediumRules=${mediumRules.size} materialSources=${materialDatabase.current().sources}"
        )
    }

    private class SourceState(
        val generation: Long,
        soundId: String?,
        profile: AcousticSourceProfile?,
        position: Vec3,
        gain: Double,
        importance: Double,
        val streaming: Boolean,
        val looping: Boolean
    ) {
        val soundId: String = soundId ?: ""
        val profile: AcousticSourceProfile = profile ?: AcousticSourceProfile.GENERIC
        val gain: Double = max(0.0, gain)
        val importance: Double = max(0.0, importance)
        @Volatile var position: Vec3 = position
        @Volatile var velocity: Vec3 = Vec3(0.0, 0.0, 0.0)
        @Volatile var lastQueuedPosition: Vec3? = null
        @Volatile var lastQueuedListener: Vec3? = null
        @Volatile var lastMoveNanos: Long = System.nanoTime()
        @Volatile var lastQueuedSceneRevision: Long = Long.MIN_VALUE
        @Volatile var lastQueuedEpoch: Long = Long.MIN_VALUE
        @Volatile var lastQueuedHadReflection: Boolean = false
        @Volatile var pcmCapture: LegacySoftwareWetRenderer.PcmCapture? = null
        @Volatile var lastEffect: LegacyEffectParameters? = null
        @Volatile var lastEffectRevision: Long = Long.MIN_VALUE
    }

    private data class SourceRequestSeed(
        val sourceId: Int,
        val generation: Long,
        val position: Vec3,
        val soundId: String,
        val profile: AcousticSourceProfile,
        val velocity: Vec3,
        val gain: Double,
        val importance: Double
    ) {
        fun platformSoundId(): String = if (soundId.isBlank()) "legacy:source/$sourceId" else soundId
    }

    private data class SourceRequest(
        val sourceId: Int,
        val generation: Long,
        val position: Vec3,
        val soundId: String,
        val profile: AcousticSourceProfile,
        val velocity: Vec3,
        val scene: AcousticScene,
        val listener: Vec3,
        val reflection: ReflectionField?,
        val epoch: Long,
        val pack: LegacyShaderPackRuntime
    )

    private data class SourceResult(
        val sourceId: Int,
        val generation: Long,
        val sceneRevision: Long,
        val epoch: Long,
        val sourcePosition: Vec3,
        val listener: Vec3,
        val soundId: String,
        val behavior: AcousticSourceProfile,
        val velocity: Vec3,
        val effect: LegacyEffectParameters,
        val direct: dev.acoustic.core.passes.DirectPathResult?,
        val rir: ImpulseResponse?,
        val early: EarlyReflectionField?,
        val foa: FoaImpulseResponse?,
        val backend: String,
        val millis: Double,
        val executionNanos: Long
    )

    private data class RoomRequest(
        val frame: PlatformFrameSnapshot,
        val sourceSeeds: List<SourceRequestSeed>,
        val effectsEnabled: Boolean,
        val pack: LegacyShaderPackRuntime,
        val performance: LegacyPerformanceTuning,
        val parallel: ParallelWorkExecutor
    )

    private data class CapturedSourceSeeds(
        val refreshRevision: Long,
        val seeds: List<SourceRequestSeed>
    )

    private companion object {
        fun newWorkers(count: Int, prefix: String): ExecutorService = Executors.newFixedThreadPool(max(1, count), object : ThreadFactory {
            private var index = 0
            @Synchronized override fun newThread(runnable: Runnable): Thread {
                val thread = Thread(runnable, prefix + index++)
                thread.isDaemon = true
                thread.priority = max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1)
                return thread
            }
        })

        fun rayBackendDiagnostics(): String {
            val all: List<GeometricExternalBackend> = GeometricBackendRegistry.all()
            if (all.isEmpty()) return "cpu-only"
            val builder = StringBuilder()
            for (backend in all) {
                if (builder.isNotEmpty()) builder.append(';')
                try {
                    builder.append(backend.id()).append(" avail=").append(backend.available())
                        .append(" traces=").append(backend.traceCount()).append(" failures=").append(backend.failureCount())
                        .append(" lastMs=").append(backend.lastTraceMillis()).append(" desc=").append(backend.description())
                    if (backend.lastFailure().isNotEmpty()) builder.append(" error=").append(backend.lastFailure())
                } catch (t: Throwable) {
                    builder.append(backend.id()).append(" probe-error=").append(t.javaClass.simpleName)
                }
            }
            return builder.toString()
        }

        fun waveBackendDiagnostics(): String {
            val all: List<FdtdExternalBackend> = FdtdBackendRegistry.all()
            if (all.isEmpty()) return "cpu-only"
            val builder = StringBuilder()
            for (backend in all) {
                if (builder.isNotEmpty()) builder.append(';')
                try {
                    builder.append(backend.id()).append(" avail=").append(backend.available())
                        .append(" solves=").append(backend.solveCount()).append(" failures=").append(backend.failureCount())
                        .append(" lastMs=").append(backend.lastSolveMillis()).append(" desc=").append(backend.description())
                    if (backend.lastFailure().isNotEmpty()) builder.append(" error=").append(backend.lastFailure())
                } catch (t: Throwable) {
                    builder.append(backend.id()).append(" probe-error=").append(t.javaClass.simpleName)
                }
            }
            return builder.toString()
        }

        fun effectiveSourceProfile(profile: AcousticSourceProfile?, shader: LegacyShaderPackRuntime?): AcousticSourceProfile {
            val base = profile ?: AcousticSourceProfile.GENERIC
            return if (shader == null) base else base.blend(shader.sourceProfileStrength())
        }

        fun fullSourceReady(state: LegacyPublishedState?, shader: LegacyShaderPackRuntime?): Boolean =
            state != null && state.ready() && shader != null && !shader.disabled() &&
                (shader.listenerInvariantPipeline().passes().isEmpty() || state.reflectionField() != null)

        fun clampVelocity(velocity: Vec3): Vec3 {
            val speed = sqrt(velocity.x * velocity.x + velocity.y * velocity.y + velocity.z * velocity.z)
            if (!(speed > 0.0) || speed <= 200.0) return velocity
            val scale = 200.0 / speed
            return Vec3(velocity.x * scale, velocity.y * scale, velocity.z * scale)
        }

        fun listenerForward(entity: Any): Vec3 {
            return try {
                val yaw = Math.toRadians(ForgeReflection.numberField(entity, "rotationYaw", "field_70177_z"))
                Vec3(-kotlin.math.sin(yaw), 0.0, kotlin.math.cos(yaw)).normalize()
            } catch (_: Throwable) { Vec3(0.0, 0.0, 1.0) }
        }

        fun listener(entity: Any): Vec3 {
            val x = ForgeReflection.numberField(entity, "posX", "field_70165_t")
            var y = ForgeReflection.numberField(entity, "posY", "field_70163_u")
            val z = ForgeReflection.numberField(entity, "posZ", "field_70161_v")
            try {
                val eye = ForgeReflection.invoke(entity, arrayOf("getEyeHeight", "func_70047_e"))
                y += (eye as Number).toDouble()
            } catch (_: RuntimeException) {}
            return Vec3(x, y, z)
        }
    }
}
