# Джерела датасету етапу 2

Порядок класів збігається з `YoloConfig.ukrainianVehicles`: 0 car, 1 truck, 2 bus, 3 trolleybus. Парсер у `:tracker-vision` цей каталог не читає.

## COCO 2017

- Зображення і бокси: [COCO](https://cocodataset.org/), анотації `instances_train2017` / `instances_val2017`.
- Беруться лише людські бокси категорій car (3), truck (8), bus (6). Натовп (`iscrowd`) відкидається.
- Автобус COCO записується як клас 2 і ніколи не перейменовується на trolleybus.
- Анотації поширюються під [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Фото лишаються під ліцензіями Flickr, з якими їх віддавали в COCO.

## Тролейбус

- Файли з категорій Wikimedia Commons `Trolleybuses` (`deepcategory`), з 1995 року, ширина від 640 і висота від 480. Ліцензії лише CC BY, CC BY-SA, CC0 або public domain. NC відкидається. Трамваї, музейні кадри і оригінали `upload.wikimedia.org` не беруться: їх або не той клас, або Commons віддає 429.
- Бокс тролейбуса — срібний: COCO-модель `yolov8n` знаходить автобус на цьому фото, і цей бокс зберігається як клас 3. Поруч залишені впевнені car і truck.
- Список URL і ліцензій пишеться в `data/cache/commons_sources.json` під час збору. Це не ручна розмітка корпусу.
