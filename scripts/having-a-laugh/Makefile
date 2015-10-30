
#FN_WD = /export/projects/twolfe/fnparse-output/experiments/precompute-features/framenet/sep29a
#PB_WD = /export/projects/twolfe/fnparse-output/experiments/precompute-features/propbank/sep14b
FN_WD = /export/projects/twolfe/fnparse-output/experiments/for-oct-tacl/framenet/oct21a
PB_WD = /export/projects/twolfe/fnparse-output/experiments/for-oct-tacl/propbank/oct21a

framenet-ig-feat.txt:
	#ssh test1b "cat $(FN_WD)/ig/products/ig-files/shard-* | sort -rg" >framenet-ig-feat.txt
	cat $(FN_WD)/ig/products/ig-files/shard-* | sort -rg >framenet-ig-feat.txt

framenet-ig-feat.dedup.txt: framenet-ig-feat.txt
	awk '$$4 == 1' <framenet-ig-feat.txt >/tmp/c1
	awk '$$4 == 2' <framenet-ig-feat.txt >/tmp/c2
	awk '$$4 == 3' <framenet-ig-feat.txt >/tmp/c3
	python dedup_sim_feats.py 5.0 /tmp/c1 300 /tmp/t1
	python dedup_sim_feats.py 6.0 /tmp/c2 200 /tmp/t2
	python dedup_sim_feats.py 6.0 /tmp/c3 200 /tmp/t3
	cat /tmp/t[123] | sort -rg >framenet-ig-feat.dedup.txt

framenet-%.fs: framenet-ig-feat.dedup.txt
	#python dedup_sim_feats.py framenet-ig-feat.txt $* framenet-$*.fs
	python poisson_feature_set.py framenet-ig-feat.dedup.txt framenet-$*.fs $* 3 0.005


propbank-ig-feat.txt:
	#ssh test1b "cat $(PB_WD)/ig/products/ig-files/shard-* | sort -rg" >propbank-ig-feat.txt
	cat $(PB_WD)/ig/products/ig-files/shard-* | sort -rg >propbank-ig-feat.txt

propbank-ig-feat.dedup.txt: propbank-ig-feat.txt
	awk '$$4 == 1' <propbank-ig-feat.txt >/tmp/c1pb
	awk '$$4 == 2' <propbank-ig-feat.txt >/tmp/c2pb
	awk '$$4 == 3' <propbank-ig-feat.txt >/tmp/c3pb
	python dedup_sim_feats.py 5.0 /tmp/c1pb 400 /tmp/t1pb
	python dedup_sim_feats.py 6.0 /tmp/c2pb 300 /tmp/t2pb
	python dedup_sim_feats.py 6.0 /tmp/c3pb 300 /tmp/t3pb
	cat /tmp/t[123]pb | sort -rg >propbank-ig-feat.dedup.txt

propbank-%.fs: propbank-ig-feat.dedup.txt
	#python dedup_sim_feats.py propbank-ig-feat.txt $* propbank-$*.fs
	python poisson_feature_set.py propbank-ig-feat.dedup.txt propbank-$*.fs $* 3 0.005


all: \
	propbank-10.fs propbank-20.fs propbank-40.fs propbank-80.fs propbank-160.fs propbank-320.fs propbank-640.fs propbank-1280.fs \
	framenet-10.fs framenet-20.fs framenet-40.fs framenet-80.fs framenet-160.fs framenet-320.fs framenet-640.fs framenet-1280.fs

# NOTE: Here is how you can tell the difference between two feature sets (e.g. if you have new IG estimates):
# DIM=10
# diff <(grep -oP '\[.+\]' propbank-${DIM}.fs | sort) <(grep -oP '\[.+\]' old/propbank-${DIM}.fs | sort)





# New method: actually solve the knapsack problem
# TODO filter out duplicates using dedup code
knapsack-test-%.fs: igprod.txt
	#awk '{print $$2, $$3}' <igprod.txt >/tmp/temp-knapsack-$*
	python dedup_sim_feats.py igprod.txt 2000 /dev/stdout \
		| grep '^<Feat' >/tmp/temp-knapsack-raw-$*
	key-values mi hx </tmp/temp-knapsack-raw-$* >/tmp/temp-knapsack-$*
	octave --eval "knapsack_feature_set('/tmp/temp-knapsack-$*', '/tmp/temp-knapsack-soln-$*', $*);"
	lines /tmp/temp-knapsack-raw-$* /tmp/temp-knapsack-soln-$* true >knapsack-test-$*.fs

knapsack-framenet-%.fs: framenet-ig-feat.txt
	python dedup_sim_feats.py framenet-ig-feat.txt 100 /dev/stdout \
		| grep '^<Feat' >/tmp/temp-knapsack-raw-framenet-$*
	key-values mi hx </tmp/temp-knapsack-raw-framenet-$* >/tmp/temp-knapsack-$*
	octave --eval "knapsack_feature_set('/tmp/temp-knapsack-$*', '/tmp/temp-knapsack-soln-$*', $*);"
	lines /tmp/temp-knapsack-raw-framnet-$* /tmp/temp-knapsack-soln-$* true >knapsack-test-$*.fs

