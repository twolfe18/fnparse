
# Reads from stdin and writes to stdout

seen = set()
with open('/dev/stdin', 'r') as f:
  for line in f:
    ar = line.rstrip().split('\t')
    assert len(ar) == 7
    #print ar

    ti = []
    ts = []
    templates_int = ar[5].split('*')
    templates_str = ar[6].split('*')
    #print templates_str
    assert len(templates_str) == int(ar[4])
    for i, s in enumerate(templates_str):
      if 'role' in s.lower():
        continue
      ti.append(templates_int[i])
      ts.append(templates_str[i])

    if len(ti) == 0:
      continue
    ar[4] = str(len(ti))
    ar[5] = '*'.join(ti)
    ar[6] = '*'.join(ts)

    if ar[5] not in seen:
      print '\t'.join(ar)
      seen.add(ar[5])


