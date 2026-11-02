package com.cais.orchestrator.support;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 目标类别名称归一：检测模型返回英文类别（person），规则里可能填中文（人员）或反过来。
 * 过滤与统计统一走这里，避免「填了中文目标类别，结果全被过滤成未检出」。
 */
public final class LabelNames {

    private static final Pattern TRACK_SUFFIX = Pattern.compile("#T\\d+");
    private static final Map<String, String> EN_TO_ZH = new HashMap<>();

    static {
        EN_TO_ZH.put("person", "人");
        EN_TO_ZH.put("bicycle", "自行车");
        EN_TO_ZH.put("car", "汽车");
        EN_TO_ZH.put("motorcycle", "摩托车");
        EN_TO_ZH.put("airplane", "飞机");
        EN_TO_ZH.put("bus", "公交车");
        EN_TO_ZH.put("train", "火车");
        EN_TO_ZH.put("truck", "卡车");
        EN_TO_ZH.put("boat", "船");
        EN_TO_ZH.put("traffic light", "交通信号灯");
        EN_TO_ZH.put("fire hydrant", "消防栓");
        EN_TO_ZH.put("stop sign", "停车标志");
        EN_TO_ZH.put("parking meter", "停车计时器");
        EN_TO_ZH.put("bench", "长椅");
        EN_TO_ZH.put("bird", "鸟");
        EN_TO_ZH.put("cat", "猫");
        EN_TO_ZH.put("dog", "狗");
        EN_TO_ZH.put("horse", "马");
        EN_TO_ZH.put("sheep", "羊");
        EN_TO_ZH.put("cow", "牛");
        EN_TO_ZH.put("elephant", "大象");
        EN_TO_ZH.put("bear", "熊");
        EN_TO_ZH.put("zebra", "斑马");
        EN_TO_ZH.put("giraffe", "长颈鹿");
        EN_TO_ZH.put("backpack", "背包");
        EN_TO_ZH.put("umbrella", "雨伞");
        EN_TO_ZH.put("handbag", "手提包");
        EN_TO_ZH.put("tie", "领带");
        EN_TO_ZH.put("suitcase", "行李箱");
        EN_TO_ZH.put("frisbee", "飞盘");
        EN_TO_ZH.put("skis", "滑雪板");
        EN_TO_ZH.put("snowboard", "单板滑雪板");
        EN_TO_ZH.put("sports ball", "运动球");
        EN_TO_ZH.put("kite", "风筝");
        EN_TO_ZH.put("baseball bat", "棒球棒");
        EN_TO_ZH.put("baseball glove", "棒球手套");
        EN_TO_ZH.put("skateboard", "滑板");
        EN_TO_ZH.put("surfboard", "冲浪板");
        EN_TO_ZH.put("tennis racket", "网球拍");
        EN_TO_ZH.put("bottle", "瓶子");
        EN_TO_ZH.put("wine glass", "酒杯");
        EN_TO_ZH.put("cup", "杯子");
        EN_TO_ZH.put("fork", "叉子");
        EN_TO_ZH.put("knife", "刀");
        EN_TO_ZH.put("spoon", "勺子");
        EN_TO_ZH.put("bowl", "碗");
        EN_TO_ZH.put("banana", "香蕉");
        EN_TO_ZH.put("apple", "苹果");
        EN_TO_ZH.put("sandwich", "三明治");
        EN_TO_ZH.put("orange", "橙子");
        EN_TO_ZH.put("broccoli", "西兰花");
        EN_TO_ZH.put("carrot", "胡萝卜");
        EN_TO_ZH.put("hot dog", "热狗");
        EN_TO_ZH.put("pizza", "披萨");
        EN_TO_ZH.put("donut", "甜甜圈");
        EN_TO_ZH.put("cake", "蛋糕");
        EN_TO_ZH.put("chair", "椅子");
        EN_TO_ZH.put("couch", "沙发");
        EN_TO_ZH.put("potted plant", "盆栽");
        EN_TO_ZH.put("bed", "床");
        EN_TO_ZH.put("dining table", "餐桌");
        EN_TO_ZH.put("toilet", "马桶");
        EN_TO_ZH.put("tv", "电视");
        EN_TO_ZH.put("laptop", "笔记本电脑");
        EN_TO_ZH.put("mouse", "鼠标");
        EN_TO_ZH.put("remote", "遥控器");
        EN_TO_ZH.put("keyboard", "键盘");
        EN_TO_ZH.put("cell phone", "手机");
        EN_TO_ZH.put("microwave", "微波炉");
        EN_TO_ZH.put("oven", "烤箱");
        EN_TO_ZH.put("toaster", "烤面包机");
        EN_TO_ZH.put("sink", "水槽");
        EN_TO_ZH.put("refrigerator", "冰箱");
        EN_TO_ZH.put("book", "书");
        EN_TO_ZH.put("clock", "时钟");
        EN_TO_ZH.put("vase", "花瓶");
        EN_TO_ZH.put("scissors", "剪刀");
        EN_TO_ZH.put("teddy bear", "泰迪熊");
        EN_TO_ZH.put("hair drier", "吹风机");
        EN_TO_ZH.put("toothbrush", "牙刷");
        EN_TO_ZH.put("进入", "进入");
        EN_TO_ZH.put("离开", "离开");
        EN_TO_ZH.put("停留", "停留");
        EN_TO_ZH.put("徘徊", "徘徊");
        EN_TO_ZH.put("奔跑", "奔跑");
        EN_TO_ZH.put("摔倒", "摔倒");
        EN_TO_ZH.put("攀爬", "攀爬");
        EN_TO_ZH.put("弯腰", "弯腰");
        EN_TO_ZH.put("蹲下", "蹲下");
        EN_TO_ZH.put("坐下", "坐下");
        EN_TO_ZH.put("搬运", "搬运");
        EN_TO_ZH.put("推拉", "推拉");
        EN_TO_ZH.put("举升", "举升");
        EN_TO_ZH.put("操作设备", "操作设备");
        EN_TO_ZH.put("开关门", "开关门");
        EN_TO_ZH.put("吸烟", "吸烟");
        EN_TO_ZH.put("打电话", "打电话");
        EN_TO_ZH.put("交谈", "交谈");
        EN_TO_ZH.put("聚集", "聚集");
        EN_TO_ZH.put("排队", "排队");
        EN_TO_ZH.put("穿越", "穿越");
        EN_TO_ZH.put("跨越", "跨越");
        EN_TO_ZH.put("挥手", "挥手");
        EN_TO_ZH.put("追逐", "追逐");
        EN_TO_ZH.put("打斗", "打斗");
        EN_TO_ZH.put("NONE", "不判断因果");
        EN_TO_ZH.put("SEQUENCE", "主语先于宾语");
        EN_TO_ZH.put("SIMULTANEOUS", "主语与宾语同时");
        EN_TO_ZH.put("CAUSE", "主语导致宾语");
    }

    private LabelNames() {
    }

    /** 去轨迹编号、英文类别转中文、转小写后的可比较文本。 */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String text = TRACK_SUFFIX.matcher(value.trim()).replaceAll("").trim();
        if (text.isEmpty()) {
            return "";
        }
        String zh = EN_TO_ZH.get(text.toLowerCase(Locale.ROOT));
        return (zh == null ? text : zh).toLowerCase(Locale.ROOT);
    }

    /** 目标类别是否命中规则配置：相同，或一方包含另一方（戴头盔人员 命中 人员）。 */
    public static boolean matches(String candidate, String wanted) {
        String left = normalize(candidate);
        String right = normalize(wanted);
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        return left.equals(right) || left.contains(right) || right.contains(left);
    }
}
