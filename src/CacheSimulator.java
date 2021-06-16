import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Objects;

public class CacheSimulator {
    public static void main(String[] args) throws IOException {
        CacheManager cacheManager = new CacheManager();
        cacheManager.manageRequests();
    }
    public static class Block {
        int blockSize;
        String blockTag;
        Boolean dirtyBit;
        Boolean emptyBlock;
        String type;


        public Block(int blockSize,String blockTag,String type){
            this.type = type;
            this.blockSize = blockSize;
            this.blockTag = blockTag;
            this.dirtyBit = false;
            this.emptyBlock = true;
        }

        public void setBlockTag(String blockTag) {
            this.blockTag = blockTag;
        }

        public void setDirtyBit(Boolean dirtyBit) {
            this.dirtyBit = dirtyBit;
        }

        @Override
        public boolean equals(Object o) {
            Block block = (Block) o;
            if (this.blockTag.equals(block.blockTag) )return true;
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(blockSize, blockTag, dirtyBit, emptyBlock);
        }
    }
    public static class Set {
        int size; //size-way associative - numbers of blocks in set
        ArrayList<Block> setBlocks;


        public Set(int size){
            this.size = size;
            setBlocks = new ArrayList<>();
        }

    }
    public static class Request {
        int type; //0,1,2
        String commandAddress;

        public Request(int type,String commandAddress){
            this.type = type;
            this.commandAddress = commandAddress;
        }
    }
    public static class OutputReport {
        //for both data and instruction
        int access;
        int miss;
        int hit ;
        int replace;
        //for traffic
        int demandFetch;
        int copiesBack;

        //all fields initialized to zero
        public OutputReport(){}
    }

    public static class Cache {
        Config cacheConfig;
        ArrayList<Set> cacheSets; //data cache
        ArrayList<Request> cacheRequests; // all the requests given to cache from input
        //address related fields
        int setNum;
        int offsetBitNum;
        int indexBitNum;
        int tagBitNum;
        //output result
        OutputReport dataReporter;
        OutputReport instructionReporter;

        public Cache(Config cacheConfig, ArrayList<Request> cacheRequests) {
            this.setCacheConfig(cacheConfig);
            this.cacheRequests = cacheRequests;
            //address related fields - for data cache
            this.setNum = (int) (cacheConfig.cacheSize / (cacheConfig.associativity * cacheConfig.blockSize));
            this.offsetBitNum = (int) (Math.log(cacheConfig.blockSize) / Math.log(2));
            this.indexBitNum = (int) (Math.log(setNum) / Math.log(2));
            this.tagBitNum = 32 - (this.indexBitNum + this.offsetBitNum);
            this.cacheSets = new ArrayList<Set>(setNum);
            makeCacheSets(setNum);
            //output result
            dataReporter = new OutputReport();
            instructionReporter = new OutputReport();
        }

        public void setCacheConfig(Config cacheConfig) {
            this.cacheConfig = cacheConfig;
        }

        public void makeCacheSets(int setNum) {
            for (int i = 0; i < setNum; i++) {
                Set set = new Set(this.cacheConfig.associativity);
                this.cacheSets.add(set);
            }
        }

        public String hexToBin(String address) {
            String binaryAddress = String.format("%32s", Integer.toBinaryString(Integer.parseInt(address, 16))).replaceAll(" ", "0");
            return binaryAddress;
        }

        public String getTag(String binaryAddress) {
            String tag;
            tag = binaryAddress.substring(0, this.tagBitNum);
            return tag;
        }

        public String getIndex(String binaryAddress) {
            String index;
            index = binaryAddress.substring(this.tagBitNum, this.tagBitNum + this.indexBitNum);
            return index;
        }

        public void handleRequest(ArrayList<Request> cacheRequests) {
            for (Request request : cacheRequests) {
                switch (request.type) {
                    case 0:
                        readData(request, 0,0);
                        break;
                    case 1:
                        writeData(request);
                        break;
                    case 2:
                        readData(request, 2,0);
                        break;
                    default:
                        System.out.println("Invalid request type");
                }
            }
        }

        public Block lookUp(int index, String tag) {
            if (!(this.cacheSets.get(index).setBlocks.size() == 0)) {
                for (Block block : this.cacheSets.get(index).setBlocks) {
                    if (block.blockTag.equals(tag))
                        return block; //hit
                }
            }
            return null;
        }

        //command type 0
        public void readData(Request request, int mode,int split) { // 0 for data , 2 for instruction
            //split if 1 , unified if 0
            String binaryAddress = hexToBin(request.commandAddress);
            String inputTag = getTag(binaryAddress); //input , new
            String indexInput = getIndex(binaryAddress);
            int indexInt = Integer.parseInt(indexInput, 2);

            if (lookUp(indexInt, inputTag) != null) {
                if (mode == 0) this.dataReporter.hit++;
                else if (mode == 2) this.instructionReporter.hit++;
                hitHandle(indexInt,inputTag);
            } else { //miss
                this.dataReporter.demandFetch += (cacheConfig.blockSize / 4);
                if (mode == 0) this.dataReporter.miss++;
                else if (mode == 2) this.instructionReporter.miss++;
                missHandle(indexInt,inputTag,mode,split);
            }
        }
        public int writePolicies (String writePolicy, String writeMissPolicy){
            int res;
            if (writePolicy.equals("wb") && writeMissPolicy.equals("wa")) res = 1;
            else if (writePolicy.equals("wb") && writeMissPolicy.equals("nw")) res = 2;
            else if (writePolicy.equals("wt") && writeMissPolicy.equals("wa")) res = 3;
            else if (writePolicy.equals("wt") && writeMissPolicy.equals("nw")) res = 4;
            else {
                res = -1;
                System.out.println("Invalid write policy");
            }
            return res;
        }
        public void writeData (Request request){
            String binaryAddress = hexToBin(request.commandAddress);
            String inputTag = getTag(binaryAddress);
            String indexInput = getIndex(binaryAddress);
            int indexInt = Integer.parseInt(indexInput, 2);

            String writePolicy = cacheConfig.writePolicy;
            String writeMissPolicy = cacheConfig.writeMissPolicy;
            int res = writePolicies(writePolicy, writeMissPolicy);

            Block writeBlock = lookUp(indexInt, inputTag);
            switch (res) {
                case 1: // write back and write allocate
                    if (writeBlock == null) { // no change in copies back - write the whole block
                        dataReporter.demandFetch += (cacheConfig.blockSize / 4);
                        dataReporter.miss++;
                        Block newBlock = missHandle(indexInt,inputTag,0,0);
                        newBlock.setDirtyBit(true);
                    } else {
                        dataReporter.hit++;
                        Block tempBlock = hitHandle(indexInt,inputTag);
                        tempBlock.setDirtyBit(true);  //writes in cache - NO copy back
                    }
                    break;
                case 2: // write back and no write allocate
                    if (writeBlock == null) { //miss - writes one word
                        dataReporter.miss++;
                        dataReporter.copiesBack++; // in word
                    } else { //hit
                        dataReporter.hit++;
                        Block tempBlock = hitHandle(indexInt,inputTag);
                        tempBlock.setDirtyBit(true);
                    }
                    break;
                case 3: // write through and write allocate - write 1 word
                    if (writeBlock == null) {
                        dataReporter.miss++;
                        dataReporter.copiesBack++;
                        dataReporter.demandFetch += (cacheConfig.blockSize / 4);
                        missHandle(indexInt,inputTag,0,0);

                    } else { //hit
                        dataReporter.hit++;
                        hitHandle(indexInt,inputTag);
                        dataReporter.copiesBack++;
                    }
                    break;
                case 4: // write through and no write allocate
                    if (writeBlock == null) {
                        dataReporter.miss++;
                        dataReporter.copiesBack++;
                    } else {
                        dataReporter.hit++;
                        hitHandle(indexInt,inputTag);
                        dataReporter.copiesBack++;
                    }
                    break;
                default:
                    System.out.println("Invalid write policies");
                    break;

            }
        }
        public void flushDirty () {
            for (Set set : cacheSets) {
                for (Block block : set.setBlocks) {
                    if (block.dirtyBit) {
                        this.dataReporter.copiesBack += (cacheConfig.blockSize / 4);
                        block.setDirtyBit(false);
                    }
                }
            }
        }
        public Block missHandle(int indexInt,String inputTag,int mode,int split){
            Block newBlock = null;
            if(mode == 0) newBlock = new Block(this.cacheConfig.blockSize, inputTag,"d");
            else if(mode == 2) newBlock = new Block(this.cacheConfig.blockSize,inputTag,"i");
            if (this.cacheSets.get(indexInt).setBlocks.size() < this.cacheConfig.associativity) {
                this.cacheSets.get(indexInt).setBlocks.add(newBlock);
            } else {
                if (this.cacheSets.get(indexInt).setBlocks.size() > this.cacheConfig.associativity) {
                    System.out.println("Block Size error");
                }
                //conflict miss
                if(split == 1){
                    if(mode == 0) this.dataReporter.replace++;
                    else if(mode == 2) this.instructionReporter.replace++;
                }else if(split == 0){
                    if(newBlock.type.equals("d") && this.cacheSets.get(indexInt).setBlocks.get(cacheConfig.associativity - 1).type.equals("d"))
                        this.dataReporter.replace++;
                    else if(newBlock.type.equals("i") && this.cacheSets.get(indexInt).setBlocks.get(cacheConfig.associativity - 1).type.equals("i"))
                        this.instructionReporter.replace++;
                    else if(newBlock.type.equals("i") && this.cacheSets.get(indexInt).setBlocks.get(cacheConfig.associativity - 1).type.equals("d"))
                        this.instructionReporter.replace++;
                    else if(newBlock.type.equals("d") && this.cacheSets.get(indexInt).setBlocks.get(cacheConfig.associativity - 1).type.equals("i"))
                        this.dataReporter.replace++;
                }
                if (this.cacheSets.get(indexInt).setBlocks.get(0).dirtyBit) {
                    dataReporter.copiesBack += (cacheConfig.blockSize / 4);
//                    this.cacheSets.get(indexInt).setBlocks.get(cacheConfig.associativity - 1).setDirtyBit(false);
                }
                this.cacheSets.get(indexInt).setBlocks.remove(0);
                this.cacheSets.get(indexInt).setBlocks.add(newBlock);
            }
            return newBlock;
        }
        public Block hitHandle(int indexInt,String inputTag){
            Block tempBlock = lookUp(indexInt,inputTag);
            int blockIndex = this.cacheSets.get(indexInt).setBlocks.indexOf(lookUp(indexInt,inputTag));
            this.cacheSets.get(indexInt).setBlocks.remove(blockIndex);
            this.cacheSets.get(indexInt).setBlocks.add(tempBlock);
            return tempBlock;
        }
    }
    public static class Config {
        int cacheSize;
        int blockSize;
        int split; //0 if split , 1 if split
        int associativity;
        String writePolicy;
        String writeMissPolicy;

        public Config(int cacheSize, int blockSize, int split, int associativity, String writePolicy, String writeMissPolicy) {
            this.cacheSize = cacheSize;
            this.blockSize = blockSize;
            this.split = split;
            this.associativity = associativity;
            this.writePolicy = writePolicy;
            this.writeMissPolicy = writeMissPolicy;
        }


        static public ArrayList<Cache> readInput() throws IOException {
            ArrayList<Cache> caches = new ArrayList<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            ArrayList<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if(line.equals("")) break;
                lines.add(line);
            }
            reader.close();

            String lineArray[] = new String[lines.size()];
            lineArray = lines.toArray(lineArray);
            String config[] = lineArray[0].trim().split(" - ");
            String size[] = lineArray[1].trim().split(" - ");
            ArrayList<Config> configs = new ArrayList<>();

            if (Integer.parseInt(config[1].trim()) == 0) {
                Config cacheConfig = new Config(Integer.parseInt(lineArray[1].trim()), Integer.parseInt(config[0].trim()),
                        Integer.parseInt(config[1].trim()), Integer.parseInt(config[2].trim()), config[3].trim(), config[4].trim());
                configs.add(cacheConfig);
            } else if (Integer.parseInt(config[1].trim()) == 1) {
                Config dataConfig = new Config(Integer.parseInt(size[1].trim()), Integer.parseInt(config[0].trim()),
                        Integer.parseInt(config[1].trim()), Integer.parseInt(config[2].trim()), config[3].trim(), config[4].trim());
                configs.add(dataConfig); // 0 index for data
                Config instructionConfig = new Config(Integer.parseInt(size[1].trim()), Integer.parseInt(config[0].trim()),
                        Integer.parseInt(config[1].trim()), Integer.parseInt(config[2].trim()), config[3].trim(), config[4].trim());
                configs.add(instructionConfig);

            }
            ArrayList<Request> cacheRequests = new ArrayList<>();
            for (int i = 2; i < lineArray.length; i++) {
                String command[] = lineArray[i].trim().split(" ");
                Request request = new Request(Integer.parseInt(command[0]), command[1]);
                cacheRequests.add(request);
            }
            Cache cache = new Cache(configs.get(0),cacheRequests);
            caches.add(cache);
            if (Integer.parseInt(config[1].trim()) == 1) {
                Cache instructionCache = new Cache(configs.get(1),cacheRequests);
                caches.add(instructionCache);
            }
            return caches;
        }
    }
    public static class SplitCache {
        Cache dataCache;
        Cache instructionCache;
        ArrayList<Request> requests;

        public SplitCache(Cache dataCache,Cache instructionCache){ 
            this.dataCache = dataCache;
            this.instructionCache = instructionCache;
            requests = dataCache.cacheRequests;
        }
        public void handleSplitRequest(ArrayList<Request> splitRequests){
            for(Request request :splitRequests){
                switch (request.type){
                    case 0 :
                        dataCache.readData(request,0,1);
                        break;
                    case 1 :
                        dataCache.writeData(request);
                        break;
                    case 2 :
                        instructionCache.readData(request,2,1);
                }
            }
        }
        public void flushDirtySplit() {
            for (Set set : dataCache.cacheSets) {
                for (Block block : set.setBlocks) {
                    if (block.dirtyBit) {
                        this.dataCache.dataReporter.copiesBack += (dataCache.cacheConfig.blockSize / 4);
                        block.setDirtyBit(false);
                    }
                }
            }
            for(Set set :instructionCache.cacheSets){
                for (Block block : set.setBlocks) {
                    if (block.dirtyBit) {
                        this.instructionCache.dataReporter.copiesBack += (instructionCache.cacheConfig.blockSize / 4);
                        block.setDirtyBit(false);
                    }
                }
            }
        }
    }

    public static class CacheManager {
        ArrayList<Cache> caches;

        public CacheManager() throws IOException {
            caches = Config.readInput();
        }
        public void manageRequests(){
            if(this.caches.size() == 1){ //unified
                caches.get(0).handleRequest(caches.get(0).cacheRequests);
                caches.get(0).flushDirty();
                printCacheConfig(1);
                printResults(0);
            }else if(this.caches.size() == 2){
                SplitCache splitCache = new SplitCache(caches.get(0),caches.get(1));
                splitCache.handleSplitRequest(caches.get(0).cacheRequests);
                splitCache.flushDirtySplit();
                printCacheConfig(2);
                printResults(1);
            }
        }
        public void printCacheConfig(int mode){ //1 for unified , 2 for split
            System.out.println("***CACHE SETTINGS***");
            if(mode == 1) System.out.println("Unified I- D-cache");
            else if(mode == 2) System.out.println("Split I- D-cache");
            if(mode == 1) System.out.println("Size: "+caches.get(0).cacheConfig.cacheSize);
            else if(mode == 2) {
                System.out.println("I-cache size: "+caches.get(1).cacheConfig.cacheSize);
                System.out.println("D-cache size: "+caches.get(0).cacheConfig.cacheSize);
            }
            System.out.println("Associativity: "+caches.get(0).cacheConfig.associativity);
            System.out.println("Block size: "+caches.get(0).cacheConfig.blockSize);
            //write policy
            if(caches.get(0).cacheConfig.writePolicy.equals("wb")) System.out.println("Write policy: "+"WRITE BACK");
            else if(caches.get(0).cacheConfig.writePolicy.equals("wt")) System.out.println("Write policy: "+"WRITE THROUGH");
            //write miss policy
            if(caches.get(0).cacheConfig.writeMissPolicy.equals("wa")) System.out.println("Allocation policy: "+"WRITE ALLOCATE");
            else if(caches.get(0).cacheConfig.writeMissPolicy.equals("nw")) System.out.println("Allocation policy: "+"WRITE NO ALLOCATE");
            System.out.println();
        }
        public void printResults(int split){
            System.out.println("***CACHE STATISTICS***");
            System.out.println("INSTRUCTIONS");
            if(split ==1 ) {
                int iAccess = (caches.get(1).instructionReporter.hit + caches.get(1).instructionReporter.miss);
                System.out.println("accesses: " + iAccess);
                System.out.println("misses: " + caches.get(1).instructionReporter.miss);
                System.out.println("miss rate: " +String.format("%.4f", calculateMissRate(caches.get(1), 1))+" (hit rate "+String.format("%.4f",calculateHitRate(caches.get(1),1))+")");
                System.out.println("replace: " + caches.get(1).instructionReporter.replace);
            }else if(split == 0){
                int i2Access = caches.get(0).instructionReporter.miss+caches.get(0).instructionReporter.hit ;
                System.out.println("accesses: " + i2Access);
                System.out.println("misses: " + caches.get(0).instructionReporter.miss);
                System.out.println("miss rate: " +String.format("%.4f", calculateMissRate(caches.get(0), 1))+" (hit rate "+String.format("%.4f",calculateHitRate(caches.get(0),1))+")");
                System.out.println("replace: " + caches.get(0).instructionReporter.replace);
            }
            System.out.println("DATA");
            int dAccess = (caches.get(0).dataReporter.hit + caches.get(0).dataReporter.miss);
            System.out.println("accesses: "+dAccess);
            System.out.println("misses: "+caches.get(0).dataReporter.miss);
            System.out.println("miss rate: "+String.format("%.4f",calculateMissRate(caches.get(0),0))+" (hit rate "+String.format("%.4f",calculateHitRate(caches.get(0),0))+")");
            System.out.println("replace: "+caches.get(0).dataReporter.replace);
            System.out.println("TRAFFIC (in words)");
            int fetch = -1;
            if(split == 1) fetch = caches.get(0).dataReporter.demandFetch + caches.get(1).dataReporter.demandFetch;
            else if(split == 0) fetch = caches.get(0).dataReporter.demandFetch;
            System.out.println("demand fetch: "+fetch);
            System.out.println("copies back: "+caches.get(0).dataReporter.copiesBack);

        }
        public double calculateMissRate(Cache cache,int mode){ // 0 for data and 1 for instruction
            if(mode == 0) {
                int miss = cache.dataReporter.miss;
                int access = (cache.dataReporter.hit + cache.dataReporter.miss);
                if(access == 0) return 0;
                double dataMissRate = ((double)miss / access);
                return dataMissRate;
            }
            if(mode == 1){
                int miss = cache.instructionReporter.miss;
                int access = (cache.instructionReporter.hit + cache.instructionReporter.miss);
                if(access == 0) return 0;
                double missRate = ((double) miss / access);
                return missRate;
            }
            return -1;
        }
        public double calculateHitRate(Cache cache,int mode){
            if(mode == 0){
                int access = (cache.dataReporter.hit + cache.dataReporter.miss);
                if(access == 0) return 0;
                double missRate = calculateMissRate(cache,0);
                double hitRate = (1 - missRate);
                return hitRate;
            }
            if(mode == 1){
                int access = (cache.instructionReporter.hit + cache.instructionReporter.miss);
                if(access == 0) return 0;
                double missRate = calculateMissRate(cache,1);
                double hitRate = (1 - missRate);
                return hitRate;
            }
            return -1;
        }
    }

}
