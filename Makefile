
# Maven on NFS is basically unusable, this is a replacement
M2_LOCAL=/state/partition1/twolfe/m2-local

jar:
	rm -f target/*.jar
	mkdir -p $(M2_LOCAL)
	time mvn -Dmaven.repo.local=$(M2_LOCAL) clean compile assembly:single -DskipTests

install:
	mvn install -DskipTests -Dgpg.skip=true





# Feature selection based on information gain
experiments/feature-information-gain/feature-sets/fs-%.txt:
	cat \
		experiments/feature-information-gain/aug31a/featIG.order1-thin30-*-of-200.txt \
		experiments/feature-information-gain/aug31a/featIG.order2-thin30-*-of-200.txt \
		experiments/feature-information-gain/aug31a/featIG.order3-thin10-*-of-200.txt \
		| sort -rg | head -n $* \
		| awk -F"\t" '{if(NR>1) printf " + "; printf "\%s", $$2}END{print ""}' \
		>experiments/feature-information-gain/feature-sets/fs-$*.txt

