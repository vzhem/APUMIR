#!/usr/bin/env python3
"""Generate the 50 distinct, dark-mode APU avatar presets.

Requires ImageMagick's `convert`; generated PNGs are 200x200 and replace the
same-named Android drawable resources. Geometry is authored in a 200x200
coordinate space and rasterized at 3x before downsampling for crisp small icons.
"""

from __future__ import annotations

import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "android-app/app/src/main/res/drawable-nodpi"
SCALE = 3

# Dark, softly varied backplates. Foreground colors are selected per design,
# not by repeating one symbol in five recolored variants.
BACKGROUNDS = [
    ("#16344A", "#0A172A"), ("#204247", "#0C1C2C"),
    ("#392B4C", "#171A31"), ("#4A2B3D", "#20182C"),
    ("#1F453C", "#10202D"), ("#283A59", "#111B32"),
    ("#364632", "#14202D"), ("#50351F", "#201A2C"),
    ("#24505A", "#101C31"), ("#49365B", "#182039"),
    ("#3D3448", "#121C31"), ("#174451", "#0C1A30"),
    ("#512F31", "#211B2D"), ("#2A4B3C", "#111C2A"),
    ("#3A4262", "#151C35"), ("#21414B", "#10192D"),
    ("#563C2A", "#211B30"), ("#3C2E58", "#17182E"),
    ("#214C3E", "#10202D"), ("#29465C", "#101A31"),
    ("#493744", "#171A30"), ("#1A4A4A", "#0D1D31"),
    ("#574034", "#211A2D"), ("#263D60", "#111A31"),
    ("#41345A", "#151A31"), ("#1B4B46", "#0E1C2D"),
    ("#51402D", "#201B2E"), ("#3E385C", "#171A34"),
    ("#2A4A55", "#101B2E"), ("#51333D", "#1E1A31"),
    ("#294B3D", "#0F1C2B"), ("#364B68", "#141D36"),
    ("#4E344E", "#1B1931"), ("#1E4A58", "#0C1B2E"),
    ("#4B412A", "#1C1B2D"), ("#333D62", "#121A32"),
    ("#254F49", "#0C1D2D"), ("#56332D", "#21182D"),
    ("#31485B", "#101A30"), ("#3F3152", "#151A31"),
    ("#4D3B2E", "#1D1B30"), ("#174253", "#0C1B2F"),
    ("#493A57", "#181A31"), ("#284B3B", "#101D2D"),
    ("#57402A", "#201A2D"), ("#293B61", "#101A33"),
    ("#234D56", "#0E1B2F"), ("#4C3145", "#1C1930"),
    ("#344A38", "#101D2A"), ("#33415F", "#121B32"),
]

GOLD = "#F4C66A"
CREAM = "#F7EEDB"
MINT = "#79DCC7"
CORAL = "#FF9078"
SKY = "#85D2F2"
LILAC = "#C5A5FA"
PINK = "#F28DB5"
LIME = "#C5E77C"
ORANGE = "#FFA94D"
BLUE = "#89AFFF"

@dataclass
class Icon:
    name: str
    accent: str
    secondary: str
    draw: Callable[[Canvas], None]

class Canvas:
    def __init__(self, accent: str, secondary: str):
        self.a = accent
        self.b = secondary
        self.light = CREAM
        self.commands: list[str] = []

    def circle(self, x, y, r, fill="none", stroke="none", width=1):
        self.commands.append(
            f"fill {fill} stroke {stroke} stroke-width {width} "
            f"circle {x},{y} {x + r},{y}"
        )

    def ellipse(self, x, y, rx, ry, fill="none", stroke="none", width=1):
        self.commands.append(
            f"fill {fill} stroke {stroke} stroke-width {width} "
            f"ellipse {x},{y} {rx},{ry} 0,360"
        )

    def path(self, d, fill="none", stroke="none", width=1):
        self.commands.append(
            f"fill {fill} stroke {stroke} stroke-width {width} "
            f"stroke-linecap round stroke-linejoin round path '{d}'"
        )

    def line(self, x1, y1, x2, y2, color=None, width=5):
        self.path(f"M {x1},{y1} L {x2},{y2}", "none", color or self.light, width)

    def polygon(self, points, fill, stroke="none", width=1):
        coords = " ".join(f"{x},{y}" for x, y in points)
        self.commands.append(
            f"fill {fill} stroke {stroke} stroke-width {width} "
            f"stroke-linejoin round polygon {coords}"
        )

    def rect(self, x1, y1, x2, y2, fill, radius=0, stroke="none", width=1):
        if radius:
            self.commands.append(
                f"fill {fill} stroke {stroke} stroke-width {width} "
                f"roundrectangle {x1},{y1} {x2},{y2} {radius},{radius}"
            )
        else:
            self.commands.append(
                f"fill {fill} stroke {stroke} stroke-width {width} "
                f"rectangle {x1},{y1} {x2},{y2}"
            )

    def star(self, x, y, r, color=None, width=4):
        c = color or self.light
        self.line(x-r, y, x+r, y, c, width)
        self.line(x, y-r, x, y+r, c, width)
        self.line(x-r*0.65, y-r*0.65, x+r*0.65, y+r*0.65, c, max(2, width-1))
        self.line(x-r*0.65, y+r*0.65, x+r*0.65, y-r*0.65, c, max(2, width-1))

    def sparkle(self, x, y, r, color=None):
        c = color or self.b
        self.path(
            f"M {x},{y-r} Q {x+2},{y-2} {x+r},{y} "
            f"Q {x+2},{y+2} {x},{y+r} Q {x-2},{y+2} {x-r},{y} "
            f"Q {x-2},{y-2} {x},{y-r} Z",
            c, "none", 1,
        )

    def finish(self):
        # A quiet inner halo/rim keeps the varied backgrounds visually related.
        self.circle(100, 100, 84, "none", "#FFFFFF22", 1.5)
        return " ".join(self.commands)


def fox(c: Canvas):
    c.polygon([(57,67),(82,76),(100,67),(118,76),(143,67),(137,104),(127,127),(100,149),(73,127),(63,104)], c.a, c.light, 4)
    c.polygon([(58,70),(54,39),(83,60),(82,78)], c.b, c.light, 3)
    c.polygon([(142,70),(146,39),(117,60),(118,78)], c.b, c.light, 3)
    c.polygon([(72,102),(100,120),(128,102),(119,132),(100,145),(81,132)], "#F9E7CF", "none")
    c.ellipse(81,99,4,6,c.light)
    c.ellipse(119,99,4,6,c.light)
    c.polygon([(95,119),(105,119),(100,126)], "#2A2330")
    c.path("M 91,128 Q 100,136 109,128", "none", "#67424C", 3)


def whale(c: Canvas):
    c.path("M 42,111 C 48,83 77,68 108,73 C 127,76 143,89 148,105 C 153,120 143,133 127,137 C 98,145 61,131 42,111 Z", c.a, c.light, 4)
    c.path("M 139,105 C 153,91 163,86 169,83 C 167,96 163,103 158,108 C 165,112 170,120 171,129 C 160,125 150,120 142,115 Z", c.b, c.light, 3)
    c.path("M 72,130 Q 91,146 113,143 L 104,154 Q 85,152 72,130 Z", c.b)
    c.circle(119,94,3,c.light)
    c.path("M 75,136 Q 93,143 112,141", "none", "#B5F4E8", 2)
    c.path("M 87,67 Q 82,58 89,51 M 100,65 Q 98,53 105,47", "none", c.light, 3)
    c.circle(88,50,2,c.b); c.circle(105,46,2,c.a)


def cat(c: Canvas):
    c.path("M 59,77 L 58,47 Q 57,39 65,44 L 88,61 Q 100,56 112,61 L 135,44 Q 143,39 142,48 L 141,78 Q 151,101 137,126 Q 121,149 100,149 Q 79,149 63,126 Q 49,102 59,77 Z", c.a, c.light, 4)
    c.path("M 73,103 Q 86,96 96,104 M 104,104 Q 115,96 127,103", "none", c.light, 4)
    c.ellipse(82,101,3,5,c.light); c.ellipse(118,101,3,5,c.light)
    c.polygon([(95,116),(105,116),(100,122)], c.b)
    c.path("M 100,122 Q 94,130 88,126 M 100,122 Q 106,130 112,126", "none", c.light, 3)
    c.line(65,119,43,115,c.light,3); c.line(66,126,44,130,c.light,3)
    c.line(135,119,157,115,c.light,3); c.line(134,126,156,130,c.light,3)


def owl(c: Canvas):
    c.path("M 55,78 L 62,49 L 87,65 Q 100,60 113,65 L 138,49 L 145,78 Q 155,102 141,126 Q 124,151 100,151 Q 76,151 59,126 Q 45,102 55,78 Z", c.a, c.light, 4)
    c.ellipse(79,97,23,27,c.light); c.ellipse(121,97,23,27,c.light)
    c.circle(83,99,9,c.b); c.circle(117,99,9,c.b)
    c.circle(85,96,3,"#FFFFFF"); c.circle(119,96,3,"#FFFFFF")
    c.polygon([(93,115),(107,115),(100,128)], GOLD)
    c.path("M 72,139 Q 100,126 128,139", "none", "#D7E7F0", 3)
    c.line(87,143,82,153,c.light,3); c.line(113,143,118,153,c.light,3)


def koi(c: Canvas):
    c.path("M 47,101 C 60,78 88,67 118,76 C 136,81 147,93 151,103 C 140,118 122,129 100,130 C 76,131 58,119 47,101 Z", c.a, c.light, 4)
    c.path("M 50,100 L 31,79 Q 27,99 34,111 L 29,131 L 52,115 Z", c.b, c.light, 3)
    c.path("M 97,78 Q 104,56 121,64 L 131,80", c.b, c.light, 3)
    c.path("M 100,126 Q 111,145 129,137 L 140,122", c.b, c.light, 3)
    c.circle(128,94,3,"#172336")
    c.path("M 75,81 Q 89,98 81,122 M 103,77 Q 117,96 106,128", "none", "#FFF2D6", 4)
    c.path("M 140,101 Q 151,98 160,102", "none", c.light, 2)


def butterfly(c: Canvas):
    c.path("M 96,97 C 70,49 41,55 47,86 C 51,105 76,110 96,105 C 71,108 55,125 66,143 C 78,160 97,138 100,111 Z", c.a, c.light, 3)
    c.path("M 104,97 C 130,49 159,55 153,86 C 149,105 124,110 104,105 C 129,108 145,125 134,143 C 122,160 103,138 100,111 Z", c.b, c.light, 3)
    c.path("M 100,77 C 89,90 90,124 100,139 C 110,124 111,90 100,77 Z", GOLD, c.light, 2)
    c.path("M 96,79 Q 83,59 75,62 M 104,79 Q 117,59 125,62", "none", c.light, 3)
    c.circle(72,89,4,"#FFF5DC"); c.circle(128,89,4,"#FFF5DC")
    c.circle(82,132,3,"#FFF5DC"); c.circle(118,132,3,"#FFF5DC")


def penguin(c: Canvas):
    c.ellipse(100,105,48,57,c.a,c.light,4)
    c.ellipse(100,113,30,40,"#F5F0E5")
    c.ellipse(77,98,7,10,c.light); c.ellipse(123,98,7,10,c.light)
    c.circle(79,99,3,"#182238"); c.circle(121,99,3,"#182238")
    c.polygon([(91,113),(109,113),(100,124)], ORANGE)
    c.path("M 74,151 L 94,146 L 90,156 L 72,160 Z M 106,146 L 126,151 L 128,160 L 110,156 Z", GOLD)
    c.path("M 56,110 Q 42,130 55,140 M 144,110 Q 158,130 145,140", "none", c.b, 5)


def turtle(c: Canvas):
    c.ellipse(100,104,43,35,c.a,c.light,4)
    c.path("M 68,105 Q 70,76 100,73 Q 130,76 132,105 Q 130,130 100,133 Q 70,130 68,105 Z", "#54B28F", c.light, 3)
    c.path("M 82,79 Q 100,96 118,79 M 70,99 Q 100,114 130,99 M 81,127 Q 100,111 119,127", "none", "#D2F1C0", 3)
    c.circle(148,103,16,c.b,c.light,3); c.circle(153,99,2,c.light)
    c.path("M 73,128 L 63,145 Q 58,153 68,155 L 80,139 M 125,128 L 135,145 Q 140,153 130,155 L 118,139", c.b, c.light, 3)
    c.path("M 70,86 L 59,76 L 58,94 M 128,86 L 139,76 L 140,94", c.b, c.light, 3)


def rabbit(c: Canvas):
    c.path("M 77,91 C 58,69 58,27 73,27 C 91,27 91,69 96,83 M 104,83 C 109,69 109,27 127,27 C 142,27 142,69 123,91", c.a, c.light, 4)
    c.path("M 74,38 Q 76,66 86,78 M 126,38 Q 124,66 114,78", "none", c.b, 5)
    c.ellipse(100,112,43,39,c.a,c.light,4)
    c.circle(85,109,3,"#243043"); c.circle(115,109,3,"#243043")
    c.circle(100,120,4,c.b)
    c.path("M 100,124 Q 91,135 84,129 M 100,124 Q 109,135 116,129", "none", "#6D5360", 3)
    c.circle(66,128,8,"#F7B8B4"); c.circle(134,128,8,"#F7B8B4")


def dragonfly(c: Canvas):
    c.path("M 99,72 Q 95,54 77,47 Q 62,42 62,58 Q 68,73 96,86 M 103,72 Q 107,54 125,47 Q 140,42 140,58 Q 134,73 106,86 M 96,95 Q 76,88 65,94 Q 54,102 66,111 Q 77,116 96,104 M 104,95 Q 124,88 135,94 Q 146,102 134,111 Q 123,116 104,104", c.b, c.light, 3)
    c.ellipse(100,100,9,35,c.a,c.light,3)
    c.circle(100,61,12,c.a,c.light,3)
    c.path("M 96,65 Q 84,48 78,45 M 104,65 Q 116,48 122,45", "none", c.light, 3)
    c.path("M 99,129 Q 94,145 100,158 Q 106,145 101,129", "none", c.a, 4)


def mountains(c: Canvas):
    c.path("M 31,145 L 79,68 L 101,101 L 126,56 L 173,145 Z", c.a, c.light, 3)
    c.path("M 62,145 L 109,91 L 168,145 Z", c.b, "none")
    c.path("M 67,87 L 79,68 L 91,86 L 82,83 L 77,91 Z M 110,85 L 126,56 L 143,88 L 131,81 L 124,91 L 119,81 Z", "#F7EEDB")
    c.circle(52,55,17,GOLD)
    c.path("M 31,156 Q 68,144 99,157 T 169,156", "none", "#8CCEE4", 4)


def cactus(c: Canvas):
    c.path("M 90,154 L 90,83 Q 90,68 101,68 Q 112,68 112,83 L 112,154 Z", c.a, c.light, 3)
    c.path("M 91,119 L 73,119 Q 65,119 65,109 L 65,94 Q 65,85 58,85 Q 51,85 51,94 L 51,113 Q 51,135 73,135 L 90,135 M 112,105 L 128,105 Q 137,105 137,95 L 137,85 Q 137,76 144,76 Q 151,76 151,85 L 151,100 Q 151,121 130,121 L 112,121", "none", c.light, 7)
    c.line(101,87,101,92,c.b,3); c.line(98,109,98,115,c.b,3); c.line(104,131,104,137,c.b,3)
    c.path("M 56,154 Q 100,142 144,154", "none", "#D6AF72", 4)
    c.circle(150,53,7,c.b)


def mushroom(c: Canvas):
    c.path("M 80,103 Q 81,122 75,143 Q 100,154 125,143 Q 119,122 120,103 Z", "#F3E5CD", c.light, 3)
    c.path("M 42,103 C 45,71 66,47 100,47 C 134,47 155,71 158,103 Q 149,117 137,112 Q 126,104 114,113 Q 100,122 86,113 Q 73,105 61,113 Q 49,118 42,103 Z", c.a, c.light, 4)
    c.circle(70,80,7,c.b); c.circle(101,68,8,GOLD); c.circle(129,83,6,c.b)
    c.path("M 76,120 Q 100,132 124,120", "none", "#D4BFAA", 3)
    c.circle(146,132,6,MINT)


def sprout(c: Canvas):
    c.path("M 100,151 C 97,124 102,98 100,74", "none", c.light, 6)
    c.path("M 100,111 C 77,109 57,92 62,70 C 84,69 101,83 100,111 Z", c.a, c.light, 3)
    c.path("M 101,94 C 103,68 124,49 147,54 C 145,78 129,95 101,94 Z", c.b, c.light, 3)
    c.path("M 100,111 Q 84,93 68,77 M 102,92 Q 122,71 139,59", "none", "#E5F3D4", 3)
    c.path("M 64,154 Q 100,144 136,154", "none", GOLD, 4)
    c.circle(53,65,4,c.light); c.circle(153,48,4,c.light)


def flower(c: Canvas):
    for x,y in [(100,61),(129,77),(127,111),(100,128),(73,111),(71,77)]:
        c.ellipse(x,y,16,25,c.a,c.light,2)
    c.circle(100,94,20,GOLD,c.light,3)
    c.path("M 100,115 Q 99,139 100,159 M 100,139 Q 82,124 72,133 M 100,145 Q 117,129 130,135", "none", MINT, 5)
    c.circle(93,90,3,"#FFF2C3"); c.circle(106,96,3,"#FFF2C3")


def comet(c: Canvas):
    c.path("M 90,61 C 108,43 138,46 151,62 C 167,81 158,109 139,121 C 119,134 93,125 84,106 C 75,91 78,73 90,61 Z", c.a, c.light, 3)
    c.path("M 91,108 Q 69,122 43,148 Q 78,141 109,126 Q 86,134 62,137 Q 88,119 100,110 Z", c.b)
    c.circle(129,78,8,c.light); c.circle(137,93,4,"#FFF4D2")
    c.sparkle(58,61,10,c.light); c.sparkle(157,137,8,GOLD)
    c.circle(69,99,2,c.b); c.circle(44,79,2,c.light)


def raincloud(c: Canvas):
    c.path("M 49,106 C 49,92 60,82 75,82 C 80,62 97,53 115,60 C 126,64 132,74 134,84 C 150,79 164,90 164,105 C 164,120 153,128 140,128 L 69,128 C 57,128 49,119 49,106 Z", "#93B9D4", c.light, 4)
    c.path("M 72,140 L 65,151 M 101,140 L 94,151 M 130,140 L 123,151", "none", c.a, 6)
    c.path("M 69,137 L 65,141 M 98,137 L 94,141 M 127,137 L 123,141", "none", "#D9F3F7", 3)
    c.circle(57,64,4,GOLD); c.sparkle(149,54,8,c.b)


def volcano(c: Canvas):
    c.path("M 36,153 L 77,78 L 91,68 Q 100,76 109,68 L 123,78 L 166,153 Z", "#786064", c.light, 4)
    c.path("M 77,78 L 91,68 Q 100,76 109,68 L 123,78 L 113,94 Q 100,101 87,94 Z", c.a, GOLD, 3)
    c.path("M 91,65 C 80,52 91,45 100,40 C 94,52 109,53 108,64", "none", "#E5D7E9", 4)
    c.path("M 82,105 L 70,133 M 101,104 L 100,144 M 119,105 L 132,135", "none", c.b, 5)
    c.circle(58,68,4,GOLD); c.circle(145,59,3,c.b)


def snowflake(c: Canvas):
    for angle in [0,60,120]:
        # Three double-ended axes form a clean, high-contrast crystalline snowflake.
        import math
        rad=math.radians(angle); dx=58*math.cos(rad); dy=58*math.sin(rad)
        c.line(100-dx,100-dy,100+dx,100+dy,c.light,5)
        for sign in (-1,1):
            px=100+sign*dx*0.62; py=100+sign*dy*0.62
            for side in (-1,1):
                bx=100+sign*dx*0.42; by=100+sign*dy*0.42
                rx=px-bx; ry=py-by
                # Rotate a short branch against the main axis.
                c.line(px,py,px-rx*0.72+side*ry*0.72,py-ry*0.72-side*rx*0.72,c.a,4)
    c.circle(100,100,8,GOLD)
    c.circle(51,52,4,c.b); c.circle(149,147,4,c.b)


def pine(c: Canvas):
    c.polygon([(100,35),(67,78),(83,78),(53,111),(82,111),(43,148),(157,148),(118,111),(147,111),(117,78),(133,78)], c.a, c.light, 3)
    c.rect(92,145,108,165,"#A97755",4)
    c.circle(100,63,5,GOLD); c.circle(75,105,4,c.b); c.circle(126,130,4,c.b)
    c.path("M 60,153 Q 100,163 140,153", "none", "#9CDAC2", 3)


def rocket(c: Canvas):
    c.path("M 100,37 C 128,50 140,81 127,115 L 100,148 L 73,115 C 60,81 72,50 100,37 Z", c.a, c.light, 4)
    c.circle(100,82,13,"#18314A",c.b,4)
    c.path("M 74,104 L 52,119 L 64,88 M 126,104 L 148,119 L 136,88", c.b, c.light, 3)
    c.path("M 88,145 L 100,167 L 112,145 Q 100,151 88,145 Z", ORANGE)
    c.path("M 95,146 L 100,159 L 105,146", "none", GOLD, 3)
    c.circle(100,82,4,c.light)


def satellite(c: Canvas):
    c.rect(79,78,121,120,c.a,8,c.light,4)
    c.rect(88,87,112,111,"#163147",3,c.b,3)
    c.path("M 80,86 L 54,66 L 39,80 L 66,103 Z M 120,112 L 146,132 L 161,118 L 134,95 Z", c.b, c.light, 3)
    for x,y in [(46,68),(57,77),(151,124),(141,115)]: c.line(x,y,x+2,y+10,GOLD,2)
    c.path("M 100,77 Q 86,54 68,52 M 120,77 Q 137,60 153,56", "none", c.light, 3)
    c.circle(66,52,3,c.a); c.circle(155,55,3,c.a)


def telescope(c: Canvas):
    c.path("M 59,67 L 135,50 L 149,77 L 75,98 Z", c.a, c.light, 4)
    c.path("M 136,51 L 157,46 L 169,72 L 150,78 Z", c.b, c.light, 3)
    c.path("M 87,95 L 112,153 M 134,87 L 116,153 M 92,119 L 129,119", "none", c.light, 5)
    c.circle(59,68,6,GOLD); c.path("M 54,156 L 151,156", "none", c.b, 3)
    c.circle(60,43,3,c.light); c.circle(43,84,2,c.a)


def planet(c: Canvas):
    c.ellipse(100,101,39,39,c.a,c.light,4)
    c.path("M 38,111 C 52,78 139,65 163,91 C 176,106 161,124 143,128 C 112,135 75,112 49,124 C 42,127 37,121 38,111 Z", "none", c.b, 8)
    c.path("M 39,111 C 54,85 131,75 153,93", "none", "#FFF1D3", 3)
    c.circle(85,87,6,GOLD); c.circle(113,114,4,"#F6E7CC")
    c.path("M 79,104 Q 91,96 102,104 M 95,126 Q 108,118 119,125", "none", "#B9A2EC", 3)


def helmet(c: Canvas):
    c.path("M 51,112 C 51,74 72,48 100,48 C 128,48 149,74 149,112 L 139,132 L 61,132 Z", c.a, c.light, 4)
    c.path("M 66,103 C 66,79 81,65 100,65 C 119,65 134,79 134,103 L 127,116 L 73,116 Z", "#17354A", c.b, 4)
    c.path("M 70,116 L 130,116 L 138,130 L 62,130 Z", c.b, c.light, 3)
    c.circle(81,126,3,GOLD); c.circle(119,126,3,GOLD)
    c.path("M 80,89 Q 93,76 112,82", "none", "#C7F0F5", 4)
    c.circle(100,151,4,c.light)


def headphones(c: Canvas):
    c.path("M 49,111 L 49,95 C 49,60 71,41 100,41 C 129,41 151,60 151,95 L 151,111", "none", c.a, 12)
    c.rect(43,95,69,137,c.b,12,c.light,3)
    c.rect(131,95,157,137,c.b,12,c.light,3)
    c.path("M 69,137 Q 76,154 97,154 L 108,154", "none", c.light, 5)
    c.circle(110,154,5,GOLD)
    c.path("M 77,68 Q 100,53 123,68", "none", "#FFFFFF", 3)


def vinyl(c: Canvas):
    c.circle(100,100,56,"#182238",c.light,4)
    for r,col in [(43,"#3B3F56"),(32,"#202A40"),(21,c.b)]:
        c.circle(100,100,r,"none",col,3)
    c.circle(100,100,12,c.a,c.light,2)
    c.circle(100,100,3,c.light)
    c.path("M 112,49 L 137,73", "none", c.light, 4)
    c.circle(141,77,5,GOLD)
    c.path("M 61,141 Q 84,158 108,154", "none", c.b, 3)


def guitar(c: Canvas):
    c.path("M 97,90 C 76,76 53,89 52,109 C 51,127 66,136 80,131 C 89,128 95,117 102,116 C 108,115 114,122 123,119 C 135,115 140,100 132,89 C 123,77 109,79 97,90 Z", c.a, c.light, 4)
    c.path("M 112,91 L 145,58 L 155,68 L 125,102", c.b, c.light, 4)
    c.circle(88,107,13,"#1B2940",c.light,3)
    c.circle(88,107,4,GOLD)
    for k in range(-2,3): c.line(100+k*2,101,151+k*2,61,c.light,1.5)
    c.path("M 72,127 Q 84,135 97,125", "none", "#FFE3AF", 3)


def piano(c: Canvas):
    c.rect(38,68,162,137,"#F5F0E5",13,c.light,4)
    for x in [56,76,96,116,136,156]: c.line(x,70,x,136,"#D2D1CC",2)
    for x in [57,97,117,157]: c.rect(x,68,x+12,107,"#263147",2)
    c.path("M 50,149 Q 100,160 150,149", "none", c.b, 4)
    c.circle(51,53,4,c.a); c.circle(149,51,4,c.a)


def microphone(c: Canvas):
    c.path("M 79,52 C 79,36 121,36 121,52 L 121,105 C 121,135 79,135 79,105 Z", c.a, c.light, 4)
    for y in [62,76,90]: c.path(f"M 84,{y} Q 100,{y+8} 116,{y}", "none", c.b, 3)
    c.path("M 65,93 L 65,104 C 65,151 135,151 135,104 L 135,93 M 100,140 L 100,159 M 80,160 L 120,160", "none", c.light, 5)
    c.circle(100,51,4,"#FFF3D5")


def camera(c: Canvas):
    c.rect(43,70,157,142,c.a,17,c.light,4)
    c.path("M 68,70 L 79,54 L 112,54 L 123,70", c.b, c.light, 3)
    c.circle(101,106,29,"#14263D",c.light,4)
    c.circle(101,106,17,c.b,c.light,3)
    c.circle(101,106,7,"#E8F2F4")
    c.circle(138,84,5,GOLD)
    c.path("M 57,129 Q 100,145 143,129", "none", "#FFD9A0", 3)


def palette(c: Canvas):
    c.path("M 103,47 C 64,43 39,69 40,102 C 41,138 69,157 101,151 C 116,148 116,136 107,129 C 98,121 107,111 119,113 C 142,116 159,101 156,82 C 152,61 130,49 103,47 Z", c.a, c.light, 4)
    for x,y,col in [(75,78,c.b),(103,68,GOLD),(129,82,"#F3EBD8"),(71,111,"#7BBEFF"),(91,132,PINK)]:
        c.circle(x,y,7,col,"#FAF0DD",2)
    c.path("M 127,139 Q 141,133 148,122", "none", c.light, 3)


def book(c: Canvas):
    c.path("M 100,64 Q 75,46 48,58 L 48,136 Q 76,127 100,146 Z M 100,64 Q 125,46 152,58 L 152,136 Q 124,127 100,146 Z", c.a, c.light, 4)
    c.path("M 100,64 L 100,146", "none", c.light, 4)
    for y in [79,94,109,123]:
        c.path(f"M 59,{y} Q 75,{y-5} 88,{y+1} M 112,{y+1} Q 126,{y-5} 141,{y}", "none", "#F8E8C4", 2.5)
    c.path("M 80,53 Q 101,42 122,53", "none", c.b, 3)


def pencil(c: Canvas):
    c.path("M 62,134 L 123,66 L 145,86 L 83,154 L 59,160 Z", c.a, c.light, 4)
    c.path("M 123,66 L 137,51 Q 143,45 150,52 L 158,60 Q 165,67 158,74 L 145,86 Z", c.b, c.light, 3)
    c.path("M 59,160 L 66,137 L 83,154 Z", "#F2D1A2", c.light, 2)
    c.line(78,137,139,70,"#FFF5DD",3)
    c.path("M 51,54 L 51,78 M 39,66 L 63,66", "none", GOLD, 3)


def paper_plane(c: Canvas):
    c.path("M 36,91 L 163,43 L 126,158 L 94,112 L 69,129 L 74,101 Z", c.a, c.light, 4)
    c.path("M 74,101 L 163,43 L 94,112 L 126,158 L 112,105 Z", c.b, c.light, 3)
    c.path("M 78,101 L 151,58", "none", "#FFF2D9", 3)
    c.circle(51,145,3,GOLD); c.circle(153,142,4,c.b)


def coffee(c: Canvas):
    c.path("M 57,80 L 134,80 L 128,137 Q 124,151 108,151 L 82,151 Q 66,151 63,137 Z", c.a, c.light, 4)
    c.path("M 134,92 L 148,92 Q 165,93 160,112 Q 157,127 131,125", "none", c.light, 5)
    c.path("M 71,91 L 128,91 L 124,131 Q 121,140 108,140 L 84,140 Q 75,140 73,131 Z", c.b)
    c.path("M 79,70 Q 68,58 80,48 M 101,70 Q 90,57 103,43 M 121,70 Q 111,58 124,48", "none", c.light, 4)
    c.path("M 57,155 Q 100,165 143,155", "none", GOLD, 3)


def ramen(c: Canvas):
    c.path("M 47,104 Q 100,123 153,104 L 143,143 Q 100,161 57,143 Z", c.a, c.light, 4)
    c.ellipse(100,104,54,18,"#F2E6D1",c.light,3)
    c.path("M 59,104 Q 100,119 141,104", "none", c.b, 4)
    c.path("M 72,101 C 80,82 91,112 99,95 C 108,77 116,109 129,92", "none", "#E7B356", 4)
    c.path("M 121,79 L 153,51 M 131,86 L 164,58", "none", "#D8B98E", 4)
    c.circle(82,99,5,c.a); c.circle(112,96,5,PINK)
    c.path("M 59,146 Q 100,161 141,146", "none", "#FFF4D8", 3)


def cupcake(c: Canvas):
    c.path("M 64,103 L 136,103 L 125,151 L 75,151 Z", c.a, c.light, 4)
    c.path("M 61,102 C 43,96 51,79 67,79 C 60,62 76,49 90,60 C 93,40 119,41 121,62 C 143,55 153,77 138,86 C 152,97 141,111 128,108 L 69,108 Z", c.b, c.light, 4)
    c.circle(100,51,6,GOLD)
    c.line(79,115,83,143,"#FFF0D4",3); c.line(100,115,100,147,"#FFF0D4",3); c.line(121,115,117,143,"#FFF0D4",3)
    c.circle(81,82,4,"#FFE9F1"); c.circle(118,73,4,"#FFE9F1")


def avocado(c: Canvas):
    c.path("M 100,42 C 119,42 125,68 136,91 C 149,119 135,152 100,158 C 65,152 51,119 64,91 C 75,68 81,42 100,42 Z", c.a, c.light, 4)
    c.path("M 100,66 C 113,66 118,85 126,103 C 136,125 122,143 100,146 C 78,143 64,125 74,103 C 82,85 87,66 100,66 Z", "#9BCE83", "none")
    c.circle(100,119,18,"#9E6845",c.light,3); c.circle(94,113,5,"#DCA16A")
    c.path("M 67,92 Q 54,103 57,119 M 133,92 Q 146,103 143,119", "none", c.b, 3)


def strawberry(c: Canvas):
    c.path("M 100,63 C 122,47 148,63 146,88 C 143,117 119,146 100,158 C 81,146 57,117 54,88 C 52,63 78,47 100,63 Z", c.a, c.light, 4)
    c.path("M 100,65 Q 87,44 69,52 Q 75,68 96,73 M 100,65 Q 112,43 132,51 Q 126,68 104,73", c.b, c.light, 3)
    for x,y in [(77,83),(104,82),(126,91),(70,105),(94,109),(117,115),(85,132),(106,137)]:
        c.path(f"M {x},{y} l 3,6", "none", "#FFE6A0", 2.5)


def balloon(c: Canvas):
    c.path("M 100,38 C 133,38 150,64 145,94 C 141,117 121,132 112,141 L 88,141 C 79,132 59,117 55,94 C 50,64 67,38 100,38 Z", c.a, c.light, 4)
    c.path("M 100,41 Q 86,75 88,140 M 100,41 Q 114,75 112,140", "none", c.b, 3)
    c.path("M 75,57 Q 100,42 125,57", "none", "#FFF2D8", 3)
    c.path("M 88,141 L 92,153 L 108,153 L 112,141 M 92,153 L 84,169 M 108,153 L 116,169", "none", c.light, 3)
    c.rect(84,164,116,174,"#9C674D",4,c.light,2)


def sailboat(c: Canvas):
    c.path("M 38,128 L 162,128 L 143,151 Q 100,166 57,151 Z", c.a, c.light, 4)
    c.line(100,53,100,129,c.light,5)
    c.path("M 94,61 L 94,119 L 52,119 Q 70,89 94,61 Z", c.b, c.light, 3)
    c.path("M 106,72 L 106,119 L 148,119 Q 132,92 106,72 Z", GOLD, c.light, 3)
    c.path("M 42,160 Q 70,151 98,160 T 155,160", "none", SKY, 4)
    c.circle(148,50,8,GOLD)


def airplane(c: Canvas):
    c.path("M 98,38 Q 106,34 110,46 L 117,86 L 156,65 Q 167,60 170,68 Q 172,75 161,81 L 122,105 L 117,142 Q 115,155 108,164 L 100,136 L 92,164 Q 85,155 83,142 L 78,105 L 39,81 Q 28,75 30,68 Q 33,60 44,65 L 83,86 L 90,46 Q 94,34 98,38 Z", c.a, c.light, 4)
    c.path("M 100,51 L 100,130", "none", "#FFECCD", 3)
    c.circle(58,124,3,c.b); c.circle(150,133,4,GOLD)
    c.path("M 57,145 Q 100,155 143,145", "none", c.b, 3)


def train(c: Canvas):
    c.path("M 72,42 Q 100,29 128,42 Q 143,50 143,75 L 143,128 Q 143,145 128,151 L 120,160 L 80,160 L 72,151 Q 57,145 57,128 L 57,75 Q 57,50 72,42 Z", c.a, c.light, 4)
    c.rect(70,57,130,101,"#18334A",10,c.b,3)
    c.circle(83,112,7,GOLD); c.circle(117,112,7,GOLD)
    c.path("M 68,128 L 132,128 M 82,151 L 68,169 M 118,151 L 132,169", "none", c.light, 5)
    c.path("M 79,73 Q 100,65 121,73", "none", "#C9F2ED", 3)


def bicycle(c: Canvas):
    c.circle(57,126,28,"none",c.light,4); c.circle(145,126,28,"none",c.light,4)
    c.path("M 57,126 L 87,83 L 109,126 L 57,126 L 94,126 L 126,82 L 145,126 M 82,83 L 102,83 M 122,82 L 137,82", "none", c.a, 5)
    c.circle(109,126,5,c.b,c.light,2); c.circle(87,83,4,GOLD)
    c.path("M 73,64 Q 100,49 127,64", "none", c.b, 3)
    c.circle(152,66,5,GOLD)


def compass(c: Canvas):
    c.circle(100,100,58,"#17233D",c.light,4)
    c.circle(100,100,45,"none",c.b,2)
    c.polygon([(100,48),(113,100),(100,152),(87,100)], c.a, c.light, 3)
    c.polygon([(100,48),(100,100),(87,100)], c.b)
    c.circle(100,100,6,GOLD)
    c.path("M 100,32 L 100,42 M 100,158 L 100,168 M 32,100 L 42,100 M 158,100 L 168,100", "none", c.light, 3)
    c.circle(100,44,3,c.light); c.circle(156,100,3,c.light)


def hourglass(c: Canvas):
    c.path("M 63,42 L 137,42 Q 136,71 111,93 L 103,100 L 111,107 Q 136,129 137,158 L 63,158 Q 64,129 89,107 L 97,100 L 89,93 Q 64,71 63,42 Z", "none", c.light, 5)
    c.path("M 70,50 L 130,50 Q 126,70 104,90 L 100,94 L 96,90 Q 74,70 70,50 Z", c.b)
    c.path("M 70,150 L 130,150 Q 126,130 104,110 L 100,106 L 96,110 Q 74,130 70,150 Z", c.a)
    c.path("M 100,92 L 100,110 M 87,125 Q 100,120 113,125", "none", GOLD, 3)
    c.circle(57,62,4,c.a); c.circle(143,139,4,c.b)


def key(c: Canvas):
    c.circle(70,82,28,"none",c.a,10)
    c.circle(70,82,13,"none",c.light,3)
    c.path("M 91,102 L 146,157 L 160,143 L 149,132 L 160,121 L 146,107 L 136,117 L 111,92", "none", c.light, 10)
    c.path("M 102,108 L 145,151", "none", c.b, 4)
    c.circle(70,82,5,GOLD)


def crown(c: Canvas):
    c.path("M 42,76 L 72,96 L 91,57 L 111,96 L 145,70 L 137,137 L 61,137 Z", c.a, c.light, 4)
    c.path("M 61,137 L 137,137 L 132,150 L 66,150 Z", GOLD, c.light, 3)
    c.circle(42,71,7,c.b,c.light,2); c.circle(91,51,7,GOLD,c.light,2); c.circle(145,65,7,c.b,c.light,2)
    c.path("M 71,119 L 126,119", "none", "#FFE9B9", 3)


def gamepad(c: Canvas):
    c.path("M 60,76 Q 68,61 83,67 L 117,67 Q 132,61 140,76 L 159,115 Q 166,132 153,141 Q 143,148 130,133 L 117,119 L 83,119 L 70,133 Q 57,148 47,141 Q 34,132 41,115 Z", c.a, c.light, 4)
    c.path("M 65,94 L 65,112 M 56,103 L 74,103", "none", c.light, 6)
    c.circle(127,94,5,c.b); c.circle(141,106,5,GOLD); c.circle(127,118,5,PINK); c.circle(113,106,5,MINT)
    c.path("M 85,79 Q 100,72 115,79", "none", "#FFF0D0", 3)


ICONS = [
    Icon("fox", ORANGE, GOLD, fox), Icon("whale", SKY, MINT, whale),
    Icon("cat", LILAC, PINK, cat), Icon("owl", GOLD, CREAM, owl),
    Icon("koi", CORAL, SKY, koi), Icon("butterfly", LILAC, MINT, butterfly),
    Icon("penguin", SKY, ORANGE, penguin), Icon("turtle", MINT, LIME, turtle),
    Icon("rabbit", PINK, CREAM, rabbit), Icon("dragonfly", SKY, LILAC, dragonfly),
    Icon("mountains", SKY, GOLD, mountains), Icon("cactus", MINT, LIME, cactus),
    Icon("mushroom", CORAL, GOLD, mushroom), Icon("sprout", LIME, MINT, sprout),
    Icon("flower", PINK, LILAC, flower), Icon("comet", ORANGE, SKY, comet),
    Icon("raincloud", SKY, MINT, raincloud), Icon("volcano", CORAL, ORANGE, volcano),
    Icon("snowflake", SKY, LILAC, snowflake), Icon("pine-tree", MINT, GOLD, pine),
    Icon("rocket", CORAL, SKY, rocket), Icon("satellite", SKY, GOLD, satellite),
    Icon("telescope", LILAC, MINT, telescope), Icon("ringed-planet", GOLD, LILAC, planet),
    Icon("space-helmet", MINT, SKY, helmet), Icon("headphones", LILAC, PINK, headphones),
    Icon("vinyl", CORAL, LILAC, vinyl), Icon("guitar", ORANGE, GOLD, guitar),
    Icon("piano", SKY, LILAC, piano), Icon("microphone", PINK, GOLD, microphone),
    Icon("camera", SKY, MINT, camera), Icon("paint-palette", CORAL, LILAC, palette),
    Icon("open-book", GOLD, SKY, book), Icon("pencil", ORANGE, MINT, pencil),
    Icon("paper-plane", SKY, GOLD, paper_plane), Icon("coffee", ORANGE, GOLD, coffee),
    Icon("ramen", CORAL, GOLD, ramen), Icon("cupcake", PINK, LILAC, cupcake),
    Icon("avocado", LIME, MINT, avocado), Icon("strawberry", CORAL, PINK, strawberry),
    Icon("balloon", PINK, LILAC, balloon), Icon("sailboat", SKY, GOLD, sailboat),
    Icon("airplane", SKY, MINT, airplane), Icon("train", CORAL, GOLD, train),
    Icon("bicycle", MINT, SKY, bicycle), Icon("compass", GOLD, CORAL, compass),
    Icon("hourglass", LILAC, GOLD, hourglass), Icon("key", GOLD, ORANGE, key),
    Icon("crown", GOLD, LILAC, crown), Icon("gamepad", MINT, CORAL, gamepad),
]


def main():
    convert = shutil.which("convert")
    if not convert:
        raise SystemExit("ImageMagick `convert` is required to regenerate avatar PNGs")
    OUT.mkdir(parents=True, exist_ok=True)
    if len(ICONS) != 50:
        raise SystemExit(f"Expected 50 designs, found {len(ICONS)}")

    for index, icon in enumerate(ICONS, start=1):
        top, bottom = BACKGROUNDS[index - 1]
        c = Canvas(icon.accent, icon.secondary)
        c.circle(100, 100, 87, "#FFFFFF08")
        # A soft, tinted halo behind the symbol; the symbol itself stays bold at
        # picker size (54 dp) and recognizable after a circular crop.
        c.circle(100, 100, 64, "#FFFFFF0D")
        icon.draw(c)
        draw = "scale 3,3 " + c.finish()
        target = OUT / f"avatar_std_{index:02d}.png"
        subprocess.run(
            [
                convert,
                "-size", "600x600",
                f"gradient:{top}-{bottom}",
                "-rotate", "90",
                "-draw", draw,
                "-resize", "200x200",
                "-strip",
                "-depth", "8",
                f"PNG24:{target}",
            ],
            check=True,
        )
        print(f"{index:02d} {icon.name}: {target.name}")


if __name__ == "__main__":
    main()
