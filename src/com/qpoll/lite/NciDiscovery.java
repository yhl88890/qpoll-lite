package com.qpoll.lite;

public final class NciDiscovery {
    public static boolean hasQPoll(String packet){
        try{
            if(!packet.startsWith("2103")||packet.length()<8)return false;
            int length=Integer.parseInt(packet.substring(4,6),16),count=Integer.parseInt(packet.substring(6,8),16);
            if(length!=1+2*count||packet.length()!=6+length*2)return false;
            for(int i=0;i<count;i++)if(Integer.parseInt(packet.substring(8+4*i,10+4*i),16)==0x71)return true;
        }catch(RuntimeException ignored){}
        return false;
    }
}
