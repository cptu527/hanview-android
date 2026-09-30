package com.hanview.translate;
import android.graphics.Rect;
import java.util.*;
public class PlacementTest {
 public static void main(String[] args) {
  List<Rect> placed = new ArrayList<>();
  // Adjacent tall source columns produce much wider Korean paragraphs.
  for(int i=0;i<8;i++) {
   Rect r=PatchPlacement.find(40+i*65,450,220,160,709,1400,6,placed);
   if(r==null) throw new AssertionError("Missing paragraph "+i);
   check(r,placed,709,1400,6); placed.add(r);
  }
  Random random=new Random(527);
  int count=0;
  for(int run=0;run<100;run++) {
   placed.clear();
   for(int i=0;i<40;i++) {
    Rect r=PatchPlacement.find(random.nextInt(900)-100,random.nextInt(1700)-100,
      72+random.nextInt(240),40+random.nextInt(350),709,1400,6,placed);
    if(r!=null){check(r,placed,709,1400,6);placed.add(r);count++;}
   }
  }
  if(PatchPlacement.find(0,0,710,10,709,1400,6,placed)!=null)throw new AssertionError("Oversize");
  System.out.println("PASS: 8 adjacent columns and "+count+" randomized placements remain inside screen without overlap");
 }
 static void check(Rect a,List<Rect> others,int w,int h,int gap){
  if(a.left<0||a.top<0||a.right>w||a.bottom>h)throw new AssertionError("Outside screen");
  for(Rect b:others)if(a.left<b.right+gap&&a.right+gap>b.left&&a.top<b.bottom+gap&&a.bottom+gap>b.top)
   throw new AssertionError("Overlap");
 }
}
