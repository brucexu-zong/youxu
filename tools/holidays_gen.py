"""生成各国法定节假日数据（供“有序”按需下载）。

数据来自开源的 python-holidays。中国大陆的放假和调休上班日取自 holiday-cn
（https://github.com/NateScarlet/holiday-cn，每天自动抓取国务院办公厅的放假通知）；
某一年还没发通知时，按惯例推算并标记为“未公布”。年份范围随运行日期滚动：去年到三年后。
用法：python tools/holidays_gen.py <输出目录>
"""
import datetime as dt
import json
import os
import re
import sys
import urllib.request

import holidays
from holidays.registry import COUNTRIES

OUT = sys.argv[1] if len(sys.argv) > 1 else "holidays"
Y = dt.date.today().year
YEARS = [Y - 1, Y, Y + 1, Y + 2, Y + 3]
ISLAMIC = re.compile(r"Eid|Arafa|Islamic|Hijri|Prophet|Ramadan|Ashura|Mawlid|Isra|Laylat|Muharram|Adha|Fitr|Mouloud|Maulid|Milad|Hari Raya|Lebaran|Kurban|Ramazan|Bayram", re.I)

CN_SRC = "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/{}.json"


def cn_official(year, out_dir):
    """国务院通知里的安排：返回 ({日期: 名称}, {调休上班日})；取不到就沿用上次生成的结果，再不行返回 None"""
    try:
        with urllib.request.urlopen(CN_SRC.format(year), timeout=30) as r:
            d = json.load(r)
        rest = {x["date"]: x["name"] for x in d.get("days", []) if x.get("isOffDay")}
        work = {x["date"] for x in d.get("days", []) if not x.get("isOffDay")}
        if rest:
            return rest, work
    except Exception as e:  # 404 = 这一年还没发通知；其他是网络问题
        if "404" not in str(e):
            print("holiday-cn", year, e)
            try:
                with open(os.path.join(out_dir, "CN.json"), encoding="utf-8") as f:
                    old = json.load(f)
                if year not in old.get("t", []):
                    rest = {r[0]: r[1] for r in old["h"] if r[0].startswith(str(year))}
                    work = {w for w in old.get("x", []) if w.startswith(str(year))}
                    if rest:
                        return rest, work
            except Exception:
                pass
    return None


def camel(s):
    return re.sub(r"(?<=[a-z])(?=[A-Z])", " ", s)


def build(code, cls_name):
    probe = holidays.country_holidays(code)
    langs = getattr(probe, "supported_languages", ()) or ()
    lang = "zh_CN" if "zh_CN" in langs else ("en_US" if "en_US" in langs else None)
    h = holidays.country_holidays(code, years=YEARS, language=lang) if lang else holidays.country_holidays(code, years=YEARS)
    rows, work, tent = {}, set(), []
    for d, name in sorted(h.items()):
        rows[d.isoformat()] = str(name)
        for ref in re.findall(r"\d{4}-\d{2}-\d{2}", str(name)):
            if re.search(r"调休|調|substitut|shift", str(name), re.I):
                work.add(ref)
    if code == "CN":
        for y in YEARS:
            off = cn_official(y, OUT)
            if off:
                rows = {k: v for k, v in rows.items() if not k.startswith(str(y))}
                work = {w for w in work if not w.startswith(str(y))}
                rows.update(off[0])
                work |= off[1]
            else:
                tent.append(y)   # 国务院还没公布这一年的安排：按惯例推算
    h_list = []
    for d in sorted(rows):
        n = rows[d]
        item = [d, n]
        if ISLAMIC.search(n):
            item.append("i")
        h_list.append(item)
    out = {"c": code, "n": camel(cls_name), "w": sorted(getattr(probe, "weekend", {5, 6})), "y": YEARS, "h": h_list}
    if work:
        out["x"] = sorted(work)
    if tent:
        out["t"] = tent
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    index, seen = [], set()
    for mod, info in sorted(COUNTRIES.items()):
        cls_name, code = info[0], info[1]
        if code in seen:
            continue
        seen.add(code)
        try:
            data = build(code, cls_name)
        except Exception as e:  # 个别国家数据出错时跳过，不影响其他国家
            print("skip", code, e)
            continue
        if not data["h"]:
            continue
        with open(os.path.join(OUT, code + ".json"), "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
        index.append([code, data["n"]])
    with open(os.path.join(OUT, "index.json"), "w", encoding="utf-8") as f:
        json.dump({"v": holidays.__version__, "y": YEARS, "list": index}, f, ensure_ascii=False, separators=(",", ":"))
    print(len(index), "countries,", YEARS)


main()
