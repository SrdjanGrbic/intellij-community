// "Make 'm' return 'java.util.AbstractList<java.lang.Object>' or predecessor" "true-preview"
import java.util.*;

class Test {

  AbstractList<Object> m(boolean b) {
    if (b) return new ArrayList<>();
    return new LinkedList<>();
  }

}