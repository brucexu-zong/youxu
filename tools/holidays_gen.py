"""生成各国法定节假日数据（供“有序”按需下载）。

数据来自开源的 python-holidays；中国大陆的放假调休以国务院办公厅通知为准
（库里还没收录的年份，用下面的 CN_OFFICIAL 补上）。
用法：python tools/holidays_gen.py <输出目录>
"""
import datetime as dt
import json
import os
import re
import sys

import holidays
from holidays.registry import COUNTRIES

OUT = sys.argv[1] if len(sys.argv) > 1 else "holidays"
Y = dt.date.today().year
YEARS = [Y - 1, Y, Y + 1, Y + 2]
ISLAMIC = re.compile(r"Eid|Arafa|Islamic|Hijri|Prophet|Ramadan|Ashura|Mawlid|Isra|Laylat|Muharram|Adha|Fitr|Mouloud|Maulid|Milad|Hari Raya|Lebaran|Kurban|Ramazan|Bayram", re.I)

# 国务院办公厅关于2027年部分节假日安排的通知（国办发明电〔2026〕10号，2026-11-05 发布）
CN_OFFICIAL = {
    2027: {
        "rest": [("2027-01-01", "2027-01-03", "元旦"), ("2027-02-05", "2027-02-13", "春节"), ("2027-04-03", "2027-04-05", "清明节"),
                 ("2027-05-01", "2027-05-05", "劳动节"), ("2027-06-09", "2027-06-09", "端午节"), ("2027-09-15", "2027-09-15", "中秋节"),
                 ("2027-10-01", "2027-10-07", "国庆节")],
        "work": ["2027-01-31", "2027-02-14", "2027-05-08", "2027-09-26", "2027-10-09"],
    },
}


def days(a, b):
    d, e = dt.date.fromisoformat(a), dt.date.fromisoformat(b)
    while d <= e:
        yield d.isoformat()
        d += dt.timedelta(days=1)


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
            if y in CN_OFFICIAL:
                rows = {k: v for k, v in rows.items() if not k.startswith(str(y))}
                work = {w for w in work if not w.startswith(str(y))}
                for a, b, n in CN_OFFICIAL[y]["rest"]:
                    for d in days(a, b):
                        rows[d] = n
                work |= set(CN_OFFICIAL[y]["work"])
            elif not any(k.startswith(str(y)) and "调休" in v for k, v in rows.items()):
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
