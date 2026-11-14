package com.shiroha.mmdskin.render.material;

import java.util.Locale;
import java.util.regex.Pattern;

/** 保守识别角色皮肤材质；排除眼部、毛发、服饰、饰品及阴影/腮红层。 */
final class SkinMaterialClassifier {
    private static final Pattern CAMEL_CASE = Pattern.compile("([a-z])([A-Z])");
    private static final String[] EXCLUDED = {
            "hair", "bangs", "ponytail", "髪", "髮", "发", "ヘア", "かみ",
            "eye", "iris", "pupil", "sclera", "eyewhite", "whiteeye", "eyelash", "lash",
            "eyelid", "eyebrow", "brow", "eyeline", "eyeliner", "eyeshadow", "lens",
            "glass", "spectacle", "highlight", "瞳", "虹彩", "虹膜", "眼白", "白目",
            "白眼", "睫", "眉", "眼睑", "眼瞼", "眼线", "眼線", "まつげ", "アイライン",
            "アイラッシュ", "ハイライト", "黒目", "黑目", "目玉", "眼球", "镜片", "眼鏡",
            "メガネ", "レンズ", "角膜",
            "mouth", "teeth", "tooth", "tongue", "gum", "lip", "口", "歯", "齿", "牙",
            "舌", "唇", "口腔", "くち",
            "cloth", "shirt", "skirt", "dress", "pant", "trouser", "short", "coat",
            "jacket", "vest", "sweater", "hoodie", "uniform", "suit", "armor", "armour",
            "metal", "belt", "strap", "glove", "shoe", "boot", "footwear", "sock",
            "stocking", "tight", "hose", "ribbon", "bow", "tie", "scarf", "hat", "cap",
            "collar", "choker", "cuff", "sleeve", "button", "zipper", "lace", "frill",
            "ornament", "accessor", "jewel", "gem", "ring", "necklace", "bracelet",
            "earring", "brooch", "badge", "pin", "wing", "tail", "horn", "weapon",
            "sword", "gun", "bag", "backpack", "mask", "headset", "headphone",
            "headband", "blush", "cheek", "tear", "shadow", "outline", "edge",
            "服", "衣", "衫", "裙", "裤", "褲", "鞋", "靴", "袜", "襪", "手套", "手袋",
            "タイツ", "ストッキング", "ニーソ", "ソックス", "リボン", "アクセ", "饰", "飾",
            "金属", "甲", "铠", "鎧", "帽", "袋", "帯", "带", "领", "領", "袖", "羽",
            "翼", "尾", "角", "剣", "剑", "銃", "枪", "槍", "紅暈", "红晕", "腮红",
            "腮紅", "頬染", "チーク", "涙", "泪", "影", "輪", "轮", "描边", "描邊",
            "当て", "章", "耳机", "耳機", "環", "环", "镯", "鐲", "链", "鏈", "紐",
            "ひも", "绳", "繩", "套", "覆", "具", "护", "護"
    };
    private static final Pattern ENGLISH_SKIN = Pattern.compile(
            "(?:^|[^a-z])(?:skin|flesh|face|body|neck|arm|arms|hand|hands|leg|legs|thigh|thighs|foot|feet|finger|fingers|shoulder|shoulders|torso)(?:[^a-z]|$)");
    private static final String[] CJK_SKIN = {
            "皮膚", "皮肤", "肌肤", "肌膚", "素肌", "肌", "はだ",
            "顔", "脸", "臉", "面部", "脸部", "臉部", "かお",
            "素体", "身体", "身體", "躯干", "軀幹", "からだ", "体",
            "腕", "手", "足", "脚", "腳", "腿", "太もも", "首"
    };

    private SkinMaterialClassifier() {}

    static boolean matches(String name) {
        if (name == null || name.isBlank()) return false;
        String label = CAMEL_CASE.matcher(name).replaceAll("$1 $2").toLowerCase(Locale.ROOT);
        for (String token : EXCLUDED) {
            if (label.contains(token)) return false;
        }
        if (ENGLISH_SKIN.matcher(label).find()) return true;
        for (String token : CJK_SKIN) {
            if (label.contains(token)) return true;
        }
        return false;
    }
}
