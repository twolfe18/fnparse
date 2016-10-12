
# Find the train documents/sentence which have some annotations.
# Intersection of previous filtering and train ids.
cat ids.traindev.txt \
	<(zgrep startdoc srl.facts.gz | cut -d' ' -f2) \
	| sort | uniq -d | shuf >ids.traindev.srl.txt

# Do a 90-10 split for a dev set.
N=`cat ids.traindev.srl.txt | wc -l`
T=`python -c "print int(0.9 * $N)"`
T1=`echo "$T + 1" | bc`

head -n $T ids.traindev.srl.txt >ids.train.txt
tail -n+$T1 ids.traindev.srl.txt >ids.dev.txt

