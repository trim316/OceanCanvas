"""Keep the established first WARN/FAIL/ERROR/TRUNCATED stop boundary."""
def first_failure(line):
    if ('P2 performance budget assertion:' in line and ('=WARN(' in line or '=FAIL(' in line)) or ('/ERROR]' in line and 'Perflib' not in line) or 'TRUNCATED' in line:
        return line.strip()
    return None
