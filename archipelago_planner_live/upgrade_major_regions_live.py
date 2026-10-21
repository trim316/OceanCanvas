from pathlib import Path
import json, shutil, time

ROOT = Path.cwd()
TARGET = ROOT / 'islands_auto.py'
REGISTRY = ROOT / 'archipelago_planner.json'
if not TARGET.exists():
    raise SystemExit('Run this from the Archipelago Planner folder (the folder containing islands_auto.py).')

text = TARGET.read_text(encoding='utf-8')
backup = ROOT / f'islands_auto.pre_major_regions_{time.strftime("%Y%m%d_%H%M%S")}.py'
shutil.copy2(TARGET, backup)

text = text.replace('VERSION = 3535\nVERSION_LABEL = "35.35"', 'VERSION = 3536\nVERSION_LABEL = "35.36"')

old = '''BRIDGE_SAFE_FIELDS = (\n    "display_name","theme","biome","landmark","settlement_role","notes",\n    "importance","terrain_character","primary_function","secondary_feature","travel_role","lore_notes","plan_number"\n)'''
new = '''BRIDGE_SAFE_FIELDS = (\n    "display_name","theme","biome","landmark","settlement_role","notes",\n    "importance","terrain_character","primary_function","secondary_feature","travel_role","lore_notes","plan_number",\n    "region","region_parent","region_kind","major_region_override"\n)'''
if old in text:
    text = text.replace(old, new, 1)
elif '"major_region_override"' not in text:
    raise SystemExit('Could not locate BRIDGE_SAFE_FIELDS block; source differs from expected v35.35.')

helper = '''def _major_region_name(e: dict) -> str:\n    # Explicit live planning override wins, including an intentional blank (unassigned).\n    if 'major_region_override' in e:\n        return str(e.get('major_region_override','') or '').strip()\n    rn=str(e.get('region','') or '').strip()\n    parent=str(e.get('region_parent','') or '').strip()\n    kind=str(e.get('region_kind','major_region') or 'major_region').strip().lower()\n    if parent and kind not in ('major_region','major region'):\n        return parent\n    return rn\n\n\n'''
anchor = "def _planner_html(root: Path, args, msg='') -> str:\n"
if 'def _major_region_name(' not in text:
    if anchor not in text:
        raise SystemExit('Could not locate planner HTML function.')
    text = text.replace(anchor, helper + anchor, 1)

old_snapshot = '''    region_summary={}\n    for key,public in islands.items():\n        rn=str(public.get('region','') or '').strip()\n        if rn:\n            region_summary.setdefault(rn,[]).append(key)'''
new_snapshot = '''    region_summary={}\n    for key,public in islands.items():\n        rn=_major_region_name(public)\n        if rn:\n            region_summary.setdefault(rn,[]).append(key)'''
text = text.replace(old_snapshot, new_snapshot, 1)

old_hot = '''        rn=str(e.get('region','') or '').strip()\n        col=_region_rgb(rn,str(e.get('theme','')),str(e.get('biome','')))'''
new_hot = '''        rn=_major_region_name(e)\n        col=_region_rgb(rn,str(e.get('theme','')),str(e.get('biome','')))'''
text = text.replace(old_hot, new_hot, 1)

old_legend = '''        first_e=reg['islands'][keys[0]] if keys else {}\n        parent=str(first_e.get('region_parent','') or '').strip()\n        kind=str(first_e.get('region_kind','') or '').strip().replace('_',' ')\n        base=' · '.join(desc[:2]) if desc else f'{len(keys)} island'+('s' if len(keys)!=1 else '')\n        hierarchy=(f'{kind.title()} · Part of {parent}' if parent else kind.title()) if kind else ''\n        sub=' · '.join(x for x in (hierarchy,base) if x)'''
new_legend = '''        base=' · '.join(desc[:2]) if desc else f'{len(keys)} island'+('s' if len(keys)!=1 else '')\n        sub='Major Region · '+base'''
text = text.replace(old_legend, new_legend, 1)

old_card = '''        rn=str(e.get('region','') or '').strip()\n        parent=str(e.get('region_parent','') or '').strip()\n        region_subline=(rn + (f' · part of {parent}' if parent else '')) if rn else 'Unassigned region'\n        col=_region_rgb(rn,str(e.get('theme','')),str(e.get('biome','')))'''
new_card = '''        rn=_major_region_name(e)\n        region_subline=rn if rn else 'Unassigned region'\n        col=_region_rgb(rn,str(e.get('theme','')),str(e.get('biome','')))'''
text = text.replace(old_card, new_card, 1)

text = text.replace('Region membership is regenerated from its current fracture topology; map labels are off by default so zooming never hides the islands.',
                    'Only major regions are shown on this map. Subregions, landmarks, enclaves and underground areas are deferred to later detail planning; map labels are off by default so zooming never hides the islands.', 1)

TARGET.write_text(text, encoding='utf-8')

transit = {'Island_095','Island_008','Island_010','Island_036','Island_063','Island_052','Island_116','Island_162'}
changed=0
if REGISTRY.exists():
    reg=json.loads(REGISTRY.read_text(encoding='utf-8'))
    for key in transit:
        e=reg.get('islands',{}).get(key)
        if isinstance(e,dict) and e.get('active',True):
            e['major_region_override']=''
            changed += 1
    REGISTRY.write_text(json.dumps(reg,indent=2)+"\n",encoding='utf-8')

print('Archipelago Planner live bridge upgraded to v35.36.')
print(f'Transit Isles removed from {changed} active island assignments; those islands are now unassigned.')
print(f'Backup created: {backup.name}')
print('Restart the planner once. After that, ChatGPT can update major-region assignments live through the existing bridge.')
