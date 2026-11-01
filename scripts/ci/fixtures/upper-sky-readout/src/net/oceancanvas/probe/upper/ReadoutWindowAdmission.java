package net.oceancanvas.probe.upper;
/** Test producer window only. Native loading, tickets, health and finalizer admission stay in the product. */
final class ReadoutWindowAdmission {
 static boolean allows(int inFlight,int fixedWindow){if(inFlight<0||fixedWindow<=0)throw new IllegalArgumentException();return inFlight<fixedWindow;}
}
