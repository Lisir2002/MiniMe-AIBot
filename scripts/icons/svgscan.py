import os, re, xml.etree.ElementTree as ET
NS='{http://www.w3.org/2000/svg}'
D='/tmp/feather/feather-4.29.0/icons'
def scan(v):
    p=os.path.join(D,v+'.svg')
    root=ET.parse(p).getroot()
    out=[]
    for el in root:
        tag=el.tag.replace(NS,'')
        if tag=='g': continue
        if tag=='path': out.append(('path', el.get('d')))
        elif tag=='circle': out.append(('circle', f"cx={el.get('cx')} cy={el.get('cy')} r={el.get('r')}"))
        elif tag=='rect': out.append(('rect', f"x={el.get('x')} y={el.get('y')} w={el.get('width')} h={el.get('height')} rx={el.get('rx')}"))
        elif tag=='polyline': out.append(('polyline', el.get('points')))
        elif tag=='polygon': out.append(('polygon', el.get('points')))
        elif tag=='line': out.append(('line', f"{el.get('x1')},{el.get('y1')} -> {el.get('x2')},{el.get('y2')}"))
        else: out.append((tag, ET.tostring(el, encoding='unicode')[:60]))
    return out
if __name__=='__main__':
    import names, sys
    for k,v in names.FEATHER_MAP.items():
        if v is None: continue
        els=scan(v)
        desc=' | '.join(f"{t}:{d}" for t,d in els)
        print(f"{k:16s} ({len(els)}) {desc}")
