
#FN_WD = /export/projects/twolfe/fnparse-output/experiments/precompute-features/framenet/sep29a
#PB_WD = /export/projects/twolfe/fnparse-output/experiments/precompute-features/propbank/sep14b
#FN_WD = /export/projects/twolfe/fnparse-output/experiments/for-oct-tacl/framenet/oct21a
#PB_WD = /export/projects/twolfe/fnparse-output/experiments/for-oct-tacl/propbank/oct21a
FN_WD = /export/projects/twolfe/fnparse-output/experiments/dec-experiments/framenet
PB_WD = /export/projects/twolfe/fnparse-output/experiments/dec-experiments/propbank


# TODO Update to penalize selectivity

framenet-ig-feat.4.txt:
	cat $(FN_WD)/ig/products/ig-files/shard-* \
		| awk -F"\t" 'BEGIN{OFS="\t"} $$2>0.001 {$$1 = (4 * $$2) - (1+$$3)^(1.4) - 0.001 * $$4; print}' \
		| sort -rg \
		>framenet-ig-feat.4.txt
framenet-ig-feat.8.txt:
	cat $(FN_WD)/ig/products/ig-files/shard-* \
		| awk -F"\t" 'BEGIN{OFS="\t"} $$2>0.001 {$$1 = (8 * $$2) - (1+$$3)^(1.4) - 0.001 * $$4; print}' \
		| sort -rg \
		>framenet-ig-feat.8.txt
framenet-ig-feat.16.txt:
	cat $(FN_WD)/ig/products/ig-files/shard-* \
		| awk -F"\t" 'BEGIN{OFS="\t"} $$2>0.001 {$$1 = (16 * $$2) - (1+$$3)^(1.4) - 0.001 * $$4; print}' \
		| sort -rg \
		>framenet-ig-feat.16.txt
framenet-ig-feat.32.txt:
	cat $(FN_WD)/ig/products/ig-files/shard-* \
		| awk -F"\t" 'BEGIN{OFS="\t"} $$2>0.001 {$$1 = (32 * $$2) - (1+$$3)^(1.4) - 0.001 * $$4; print}' \
		| sort -rg \
		>framenet-ig-feat.32.txt
framenet-ig-feat.64.txt:
	cat $(FN_WD)/ig/products/ig-files/shard-* \
		| awk -F"\t" 'BEGIN{OFS="\t"} $$2>0.001 {$$1 = (64 * $$2) - (1+$$3)^(1.4) - 0.001 * $$4; print}' \
		| sort -rg \
		>framenet-ig-feat.64.txt
framenet-ig-feat.4.dedup.txt: framenet-ig-feat.4.txt
	python dedup_sim_feats.py 7.0 framenet-ig-feat.4.txt 2560 framenet-ig-feat.4.dedup.txt
framenet-ig-feat.8.dedup.txt: framenet-ig-feat.8.txt
	python dedup_sim_feats.py 7.0 framenet-ig-feat.8.txt 2560 framenet-ig-feat.8.dedup.txt
framenet-ig-feat.16.dedup.txt: framenet-ig-feat.16.txt
	python dedup_sim_feats.py 7.0 framenet-ig-feat.16.txt 2560 framenet-ig-feat.16.dedup.txt
framenet-ig-feat.32.dedup.txt: framenet-ig-feat.32.txt
	python dedup_sim_feats.py 7.0 framenet-ig-feat.32.txt 2560 framenet-ig-feat.32.dedup.txt
framenet-ig-feat.64.dedup.txt: framenet-ig-feat.64.txt
	python dedup_sim_feats.py 7.0 framenet-ig-feat.64.txt 2560 framenet-ig-feat.64.dedup.txt

framenet-4-%.fs: framenet-ig-feat.4.dedup.txt
	head -n $* framenet-ig-feat.4.dedup.txt >framenet-4-$*.fs
framenet-8-%.fs: framenet-ig-feat.8.dedup.txt
	head -n $* framenet-ig-feat.8.dedup.txt >framenet-8-$*.fs
framenet-16-%.fs: framenet-ig-feat.16.dedup.txt
	head -n $* framenet-ig-feat.16.dedup.txt >framenet-16-$*.fs
framenet-32-%.fs: framenet-ig-feat.32.dedup.txt
	head -n $* framenet-ig-feat.32.dedup.txt >framenet-32-$*.fs
framenet-64-%.fs: framenet-ig-feat.64.dedup.txt
	head -n $* framenet-ig-feat.64.dedup.txt >framenet-64-$*.fs
framenet-mix-%.fs: framenet-ig-feat.8.dedup.txt framenet-ig-feat.16.dedup.txt framenet-ig-feat.32.dedup.txt
	head -n $* framenet-ig-feat.4.dedup.txt >/tmp/mix-$*.temp
	head -n $* framenet-ig-feat.8.dedup.txt >/tmp/mix-$*.temp
	head -n $* framenet-ig-feat.16.dedup.txt >>/tmp/mix-$*.temp
	#head -n $* framenet-ig-feat.32.dedup.txt >>/tmp/mix-$*.temp
	sort -u /tmp/mix-$*.temp >framenet-mix-$*.fs

framenet-all-features:
	make -j 5 framenet-ig-feat.4.dedup.txt framenet-ig-feat.8.dedup.txt framenet-ig-feat.16.dedup.txt framenet-ig-feat.32.dedup.txt framenet-ig-feat.64.dedup.txt
	make framenet-4-40.fs framenet-4-160.fs framenet-4-320.fs framenet-4-640.fs framenet-4-1280.fs framenet-4-2560.fs
	make framenet-8-40.fs framenet-8-160.fs framenet-8-320.fs framenet-8-640.fs framenet-8-1280.fs framenet-8-2560.fs
	make framenet-16-40.fs framenet-16-160.fs framenet-16-320.fs framenet-16-640.fs framenet-16-1280.fs framenet-16-2560.fs
	make framenet-32-40.fs framenet-32-160.fs framenet-32-320.fs framenet-32-640.fs framenet-32-1280.fs framenet-32-2560.fs
	make framenet-64-40.fs framenet-64-160.fs framenet-64-320.fs framenet-64-640.fs framenet-64-1280.fs framenet-64-2560.fs
	make framenet-mix-40.fs framenet-mix-160.fs framenet-mix-320.fs framenet-mix-640.fs framenet-mix-1280.fs framenet-mix-2560.fs



propbank-ig-feat.4.txt:
	cat $(PB_WD)/ig/products/ig-files/shard-* \
		| awk -F"\t" 'BEGIN{OFS="\t"} $$2>0.001 {$$1 = (4 * $$2) - (1+$$3)^(1.4) - 0.001 * $$4; print}' \
		| sort -rg \
		>propbank-ig-feat.4.txt
propbank-ig-feat.8.txt:
	cat $(PB_WD)/ig/products/ig-files/shard-* \
		| awk -F"\t" 'BEGIN{OFS="\t"} $$2>0.001 {$$1 = (8 * $$2) - (1+$$3)^(1.4) - 0.001 * $$4; print}' \
		| sort -rg \
		>propbank-ig-feat.8.txt
propbank-ig-feat.16.txt:
	cat $(PB_WD)/ig/products/ig-files/shard-* \
		| awk -F"\t" 'BEGIN{OFS="\t"} $$2>0.001 {$$1 = (16 * $$2) - (1+$$3)^(1.4) - 0.001 * $$4; print}' \
		| sort -rg \
		>propbank-ig-feat.16.txt
propbank-ig-feat.32.txt:
	cat $(PB_WD)/ig/products/ig-files/shard-* \
		| awk -F"\t" 'BEGIN{OFS="\t"} $$2>0.001 {$$1 = (32 * $$2) - (1+$$3)^(1.4) - 0.001 * $$4; print}' \
		| sort -rg \
		>propbank-ig-feat.32.txt
propbank-ig-feat.64.txt:
	cat $(PB_WD)/ig/products/ig-files/shard-* \
		| awk -F"\t" 'BEGIN{OFS="\t"} $$2>0.001 {$$1 = (64 * $$2) - (1+$$3)^(1.4) - 0.001 * $$4; print}' \
		| sort -rg \
		>propbank-ig-feat.64.txt
propbank-ig-feat.4.dedup.txt: propbank-ig-feat.4.txt
	python dedup_sim_feats.py 7.0 propbank-ig-feat.4.txt 2560 propbank-ig-feat.4.dedup.txt
propbank-ig-feat.8.dedup.txt: propbank-ig-feat.8.txt
	python dedup_sim_feats.py 7.0 propbank-ig-feat.8.txt 2560 propbank-ig-feat.8.dedup.txt
propbank-ig-feat.16.dedup.txt: propbank-ig-feat.16.txt
	python dedup_sim_feats.py 7.0 propbank-ig-feat.16.txt 2560 propbank-ig-feat.16.dedup.txt
propbank-ig-feat.32.dedup.txt: propbank-ig-feat.32.txt
	python dedup_sim_feats.py 7.0 propbank-ig-feat.32.txt 2560 propbank-ig-feat.32.dedup.txt
propbank-ig-feat.64.dedup.txt: propbank-ig-feat.64.txt
	python dedup_sim_feats.py 7.0 propbank-ig-feat.64.txt 2560 propbank-ig-feat.64.dedup.txt


# special case modification
removeK: propbank-ig-feat.4.dedup.txt
	@echo "propbank"
	python remove_role.py <propbank-ig-feat.4.dedup.txt >temp && mv temp propbank-ig-feat.4.dedup.txt
	python remove_role.py <propbank-ig-feat.8.dedup.txt >temp && mv temp propbank-ig-feat.8.dedup.txt
	python remove_role.py <propbank-ig-feat.16.dedup.txt >temp && mv temp propbank-ig-feat.16.dedup.txt
	python remove_role.py <propbank-ig-feat.32.dedup.txt >temp && mv temp propbank-ig-feat.32.dedup.txt
	python remove_role.py <propbank-ig-feat.64.dedup.txt >temp && mv temp propbank-ig-feat.64.dedup.txt
	@echo "framenet"
	python remove_role.py <framenet-ig-feat.4.dedup.txt >temp && mv temp framenet-ig-feat.4.dedup.txt
	python remove_role.py <framenet-ig-feat.8.dedup.txt >temp && mv temp framenet-ig-feat.8.dedup.txt
	python remove_role.py <framenet-ig-feat.16.dedup.txt >temp && mv temp framenet-ig-feat.16.dedup.txt
	python remove_role.py <framenet-ig-feat.32.dedup.txt >temp && mv temp framenet-ig-feat.32.dedup.txt
	python remove_role.py <framenet-ig-feat.64.dedup.txt >temp && mv temp framenet-ig-feat.64.dedup.txt


propbank-4-%.fs: propbank-ig-feat.4.dedup.txt
	head -n $* propbank-ig-feat.4.dedup.txt >propbank-4-$*.fs
propbank-8-%.fs: propbank-ig-feat.8.dedup.txt
	head -n $* propbank-ig-feat.8.dedup.txt >propbank-8-$*.fs
propbank-16-%.fs: propbank-ig-feat.16.dedup.txt
	head -n $* propbank-ig-feat.16.dedup.txt >propbank-16-$*.fs
propbank-32-%.fs: propbank-ig-feat.32.dedup.txt
	head -n $* propbank-ig-feat.32.dedup.txt >propbank-32-$*.fs
propbank-64-%.fs: propbank-ig-feat.64.dedup.txt
	head -n $* propbank-ig-feat.64.dedup.txt >propbank-64-$*.fs
propbank-mix-%.fs: propbank-ig-feat.8.dedup.txt propbank-ig-feat.16.dedup.txt propbank-ig-feat.32.dedup.txt
	head -n $* propbank-ig-feat.4.dedup.txt >/tmp/mix-$*.temp
	head -n $* propbank-ig-feat.8.dedup.txt >/tmp/mix-$*.temp
	head -n $* propbank-ig-feat.16.dedup.txt >>/tmp/mix-$*.temp
	#head -n $* propbank-ig-feat.32.dedup.txt >>/tmp/mix-$*.temp
	sort -u /tmp/mix-$*.temp >propbank-mix-$*.fs

propbank-all-features:
	make -j 5 propbank-ig-feat.4.dedup.txt propbank-ig-feat.8.dedup.txt propbank-ig-feat.16.dedup.txt propbank-ig-feat.32.dedup.txt propbank-ig-feat.64.dedup.txt
	make propbank-4-40.fs propbank-4-160.fs propbank-4-320.fs propbank-4-640.fs propbank-4-1280.fs propbank-4-2560.fs
	make propbank-8-40.fs propbank-8-160.fs propbank-8-320.fs propbank-8-640.fs propbank-8-1280.fs propbank-8-2560.fs
	make propbank-16-40.fs propbank-16-160.fs propbank-16-320.fs propbank-16-640.fs propbank-16-1280.fs propbank-16-2560.fs
	make propbank-32-40.fs propbank-32-160.fs propbank-32-320.fs propbank-32-640.fs propbank-32-1280.fs propbank-32-2560.fs
	make propbank-64-40.fs propbank-64-160.fs propbank-64-320.fs propbank-64-640.fs propbank-64-1280.fs propbank-64-2560.fs
	make propbank-mix-40.fs propbank-mix-160.fs propbank-mix-320.fs propbank-mix-640.fs propbank-mix-1280.fs propbank-mix-2560.fs




propbank-clean:
	rm -f propbank-ig-feat.8.txt propbank-ig-feat.16.txt propbank-ig-feat.32.txt propbank-ig-feat.64.txt
	rm -f propbank-ig-feat.8.dedup.txt propbank-ig-feat.16.dedup.txt propbank-ig-feat.32.dedup.txt propbank-ig-feat.64.dedup.txt
	rm -f propbank-4-40.fs propbank-4-160.fs propbank-4-320.fs propbank-4-640.fs propbank-4-1280.fs propbank-4-2560.fs
	rm -f propbank-8-40.fs propbank-8-160.fs propbank-8-320.fs propbank-8-640.fs propbank-8-1280.fs propbank-8-2560.fs
	rm -f propbank-16-40.fs propbank-16-160.fs propbank-16-320.fs propbank-16-640.fs propbank-16-1280.fs propbank-16-2560.fs
	rm -f propbank-32-40.fs propbank-32-160.fs propbank-32-320.fs propbank-32-640.fs propbank-32-1280.fs propbank-32-2560.fs
	rm -f propbank-64-40.fs propbank-64-160.fs propbank-64-320.fs propbank-64-640.fs propbank-64-1280.fs propbank-64-2560.fs
	rm -f propbank-mix-40.fs propbank-mix-160.fs propbank-mix-320.fs propbank-mix-640.fs propbank-mix-1280.fs propbank-mix-2560.fs

framenet-clean:
	rm -f framenet-ig-feat.8.txt framenet-ig-feat.16.txt framenet-ig-feat.32.txt framenet-ig-feat.64.txt
	rm -f framenet-ig-feat.8.dedup.txt framenet-ig-feat.16.dedup.txt framenet-ig-feat.32.dedup.txt framenet-ig-feat.64.dedup.txt
	rm -f framenet-4-40.fs framenet-4-160.fs framenet-4-320.fs framenet-4-640.fs framenet-4-1280.fs framenet-4-2560.fs
	rm -f framenet-8-40.fs framenet-8-160.fs framenet-8-320.fs framenet-8-640.fs framenet-8-1280.fs framenet-8-2560.fs
	rm -f framenet-16-40.fs framenet-16-160.fs framenet-16-320.fs framenet-16-640.fs framenet-16-1280.fs framenet-16-2560.fs
	rm -f framenet-32-40.fs framenet-32-160.fs framenet-32-320.fs framenet-32-640.fs framenet-32-1280.fs framenet-32-2560.fs
	rm -f framenet-64-40.fs framenet-64-160.fs framenet-64-320.fs framenet-64-640.fs framenet-64-1280.fs framenet-64-2560.fs
	rm -f framenet-mix-40.fs framenet-mix-160.fs framenet-mix-320.fs framenet-mix-640.fs framenet-mix-1280.fs framenet-mix-2560.fs

clean: propbank-clean framenet-clean

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
	lines /tmp/temp-knapsack-raw-framenet-$* /tmp/temp-knapsack-soln-$* true >knapsack-test-$*.fs

