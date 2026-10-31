"""Who spawns which particle effect in a Bedrock pack: lists roots (effects nothing in the pack spawns)."""
import collections
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bedrock_json as bj  # noqa: E402


def walk_effects(node, out):
    """Collect particle_effect.effect references in a particle event tree."""
    if isinstance(node, dict):
        pe = node.get('particle_effect')
        if isinstance(pe, dict) and 'effect' in pe:
            out.append(pe['effect'])
        for v in node.values():
            walk_effects(v, out)
    elif isinstance(node, list):
        for v in node:
            walk_effects(v, out)


def main(root):
    defs = {}
    spawns = collections.defaultdict(set)
    for dp, _, fs in os.walk(os.path.join(root, 'particles')):
        for f in fs:
            p = os.path.join(dp, f)
            pe = bj.load(p)['particle_effect']
            ident = pe['description']['identifier']
            defs[ident] = os.path.relpath(p, root)
            refs = []
            walk_effects(pe.get('events', {}), refs)
            for r in refs:
                spawns[r].add(ident)
    # entity / attachable / animation short-name maps
    ext = collections.defaultdict(set)
    for dp, _, fs in os.walk(root):
        if '/particles' in dp:
            continue
        for f in fs:
            if not f.endswith('.json'):
                continue
            p = os.path.join(dp, f)
            try:
                d = bj.load(p)
            except Exception:
                continue
            text = json.dumps(d)
            for ident in defs:
                if '"' + ident + '"' in text:
                    ext[ident].add(os.path.relpath(p, root))
    roots = [i for i in defs if not spawns.get(i)]
    print(f'{len(defs)} effects, {len(roots)} not spawned by another particle')
    for i in sorted(roots):
        where = sorted(ext.get(i, ()))
        print(f'  {i:55s} {", ".join(where[:3]) if where else "(vanilla trigger or unused)"}')


if __name__ == '__main__':
    main(sys.argv[1])
