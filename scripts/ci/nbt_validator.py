#!/usr/bin/env python3
"""Bounded big-endian NBT validator for offline region compiler inputs.

Structural validation only. Does not certify Minecraft chunk schema or gameplay.
"""
import struct

class NBTError(ValueError): pass

class Reader:
    def __init__(self,data):
        if not isinstance(data,bytes) or len(data)>16*1024*1024: raise NBTError("invalid input size")
        self.data=data;self.pos=0;self.nodes=0
    def take(self,n):
        if n<0 or n>len(self.data)-self.pos: raise NBTError("truncated or invalid length")
        x=self.data[self.pos:self.pos+n];self.pos+=n;return x
    def number(self,fmt):
        return struct.unpack(fmt,self.take(struct.calcsize(fmt)))[0]
    def string(self):
        n=self.number(">H")
        return self.take(n).decode("utf-8")
    def payload(self,t,depth=0):
        self.nodes+=1
        if self.nodes>1000000 or depth>64: raise NBTError("NBT resource limit")
        if t in (1,2,3,4,5,6):
            self.take({1:1,2:2,3:4,4:8,5:4,6:8}[t])
        elif t==7:
            self.take(self.number(">i"))
        elif t==8:
            self.string()
        elif t==9:
            elem=self.number(">B");count=self.number(">i")
            if count<0 or elem>12 or (elem==0 and count): raise NBTError("invalid list")
            if count>1000000: raise NBTError("oversized list")
            for _ in range(count): self.payload(elem,depth+1)
        elif t==10:
            while True:
                child=self.number(">B")
                if child==0: break
                if child>12: raise NBTError("unknown tag")
                self.string();self.payload(child,depth+1)
        elif t in (11,12):
            count=self.number(">i")
            if count<0: raise NBTError("negative array")
            self.take(count*(4 if t==11 else 8))
        else: raise NBTError("unknown tag")

def validate_nbt(data):
    try:
        r=Reader(data)
        if r.number(">B")!=10: raise NBTError("root must be compound")
        r.string()
        r.payload(10)
        if r.pos!=len(data): raise NBTError("trailing data")
        return True
    except (UnicodeDecodeError,struct.error) as e:
        raise NBTError("invalid NBT encoding") from e
