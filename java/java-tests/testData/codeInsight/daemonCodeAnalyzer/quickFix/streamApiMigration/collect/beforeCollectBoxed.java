// "Replace with collect" "true-preview"

import java.util.ArrayList;
import java.util.List;

public class Main {
  public void testPrimitiveMap(List<String> data) {
    List<Integer> list = new ArrayList<>();
    for(String str : d<caret>ata) {
      if(str.startsWith("xyz")) {
        int len = str.length();
        if(len > 10) {
          list.add(len);
        }
      }
    }
  }
}