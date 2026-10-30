import re

def next_sampler_phase(root):
    phases=[int(m.group(1)) for p in root.iterdir()
            if (m := re.fullmatch(r'host-sampler-(\d+)(?:\.jsonl|\.stop|-stderr\.txt)', p.name))]
    return max(phases, default=0)+1
