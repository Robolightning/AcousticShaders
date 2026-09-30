# Acoustic Shaders

**Acoustic Shaders** — открытый фреймворк физической акустики для Minecraft с моделью разработки, похожей на графические shader packs. Пользователь выбирает **Acoustic Shader Pack**, обычные ресурспаки могут задавать акустические свойства материалов и категорий источников, а runtime выполняет выбранный pipeline на CPU, CUDA, OpenCL или безопасном fallback.

Версия `0.3.0` целенаправленно выпускается для **Minecraft 1.12.2 + Forge 14.23.5.2864**. Физическое ядро и API остаются независимыми от Minecraft, но поддержка более новых версий не входит в scope этого релиза и не заявляется.

**Автор:** Robolightning  
**Лицензия:** MIT. Разрешено использовать, изменять, распространять, форкать и встраивать проект без ограничений лицензии; программное обеспечение предоставляется без каких-либо гарантий и ответственности. Полный текст — в [`LICENSE`](LICENSE).

## Основные возможности

Reference Acoustic Shader умеет частотно-зависимое прохождение сквозь стены, точную геометрию неполных блоков, дифракцию, многократные отражения, early/late response, MODAL/FDTD low-frequency wave simulation, wave/ray hybridization, отдельную обработку взрывов/снарядов/шагов/машин и адаптивные CPU/GPU budgets.

Пресеты: `POTATO`, `LOW`, `MEDIUM`, `HIGH`, `ULTRA`, `MAXIMUM`. `ULTRA`/`MAXIMUM` ориентированы прежде всего на качество и мощное железо, но оптимизация и безопасное распределение вычислений сохраняются на всех профилях.

GPU backends: настоящий CUDA Driver API + NVRTC для NVIDIA и OpenCL для совместимых NVIDIA/AMD/Intel GPU. `AUTO` выбирает `CUDA -> OpenCL -> multicore CPU -> scalar CPU`; каждый GPU backend проходит CPU-equivalence self-test и безопасно отключается при ошибке.

Захват мира в 1.12.2 тоже ограничен по времени кадра: при входе в мир acoustic volume строится постепенно **от слушателя наружу**, а не сканируется целиком одним клиентским тиком. `CAPTURE_BUDGET_MS` задаёт бюджет main-thread на тик; rolling snapshot переиспользует перекрывающуюся геометрию, а чтение блоков кеширует уже найденные chunks. Итоговая геометрия и качество ULTRA/MAXIMUM от этого не урезаются.

## Материалы и источники

Acoustic Shader отвечает за алгоритм. Обычный Minecraft Resource Pack может добавлять:

```text
assets/<namespace>/acoustic_materials/*.json
assets/<namespace>/acoustic_media/*.json
assets/<namespace>/acoustic_sources/*.json
```

Мод всегда поддерживает нижний автоматически сгенерированный pack **Acoustic Shaders Default Materials**. Он кеширует свойства vanilla/modded block states на основе registry ID, Material/SoundType, OreDictionary, геометрии и консервативных физических эвристик. Объёмные среды (`AIR/WATER/LAVA` и modded fluids) отделены от surface material и могут задавать плотность, скорость звука и 8-полосное поглощение. Пользовательские ресурспаки накладываются поверх сгенерированной базы.

## Отладка

В релизе подробные логи выключены. Для разработки Acoustic Shader/Resource Pack включи:

```properties
debug=true
```

в `.minecraft/config/acousticshaders/runtime.properties`.

Тогда появятся подробные timings, GPU diagnostics, path thickness/transmission, source profiles и медленные shader passes. Test harness включает этот режим автоматически при сборе отчётов.

## Документация

Оглавление: [`docs/README.md`](docs/README.md). Спецификация: [`spec/acoustic-shader-spec-0.3.md`](spec/acoustic-shader-spec-0.3.md).

## Реальный Forge 1.12.2 client gate

Для целевого Forge `14.23.5.2864` есть отдельный официальный-binary preflight:

```bash
ACOUSTIC_FORGE1122_BUNDLE=/path/to/forge.tar.xz ./dev-forge1122-client-preflight.sh
```

Он проверяет exact installer/MDK/universal, SHA/embedded JAR, LaunchWrapper/FMLTweaker metadata и настоящий Forge event/GUI ABI. Если дополнительно задан `ACOUSTIC_MC1122_HOME` с полным launcher runtime Minecraft 1.12.2, gate проверяет checksum каждого выбранного Windows library/native artifact и строит точный client classpath.

`dev-forge1122-client-launch.sh` — следующий аппаратно-независимый уровень: при наличии полного launcher runtime, WinLab, Windows Java 8, MixinBooter 11.15 и Forgelin-Continuous 2.4.0.0 он запускает настоящий `net.minecraft.launchwrapper.Launch`/`FMLTweaker` и требует достижения FML initialization. Отсутствующий vanilla/library artifact считается внешним runtime gap и выводится отдельным списком, а не маскируется contract stubs.
