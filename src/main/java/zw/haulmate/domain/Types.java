package zw.haulmate.domain;

/** All enumerations used across the platform. */
public final class Types {
    private Types() {}

    public enum Role { SHIPPER, DRIVER, FLEET_OWNER, ADMIN }
    public enum UserStatus { PENDING_KYC, ACTIVE, SUSPENDED }
    public enum KycStatus { NONE, PENDING, VERIFIED, REJECTED }
    public enum DocType { NATIONAL_ID, PASSPORT, DRIVERS_LICENCE, SELFIE, LIVENESS_FRAME, VEHICLE_REG, INSURANCE, ZINARA_DISC }
    public enum CaptureMode { LIVE, UPLOAD }
    public enum DocStatus { PENDING, PROCESSING, AUTO_PASSED, AUTO_FLAGGED, APPROVED, REJECTED }

    /** Truck classes, ordered by capacity. rank() is used for compatibility matching. */
    public enum TruckClass {
        PICKUP_1T(1, "1 t pickup / bakkie"),
        LIGHT_3T(3, "3-5 t light truck"),
        MEDIUM_8T(8, "8-10 t medium truck"),
        HEAVY_15T(15, "15-20 t rigid / tipper"),
        SUPER_30T(30, "30 t+ horse & trailer");

        public final int maxTonnes;
        public final String label;
        TruckClass(int maxTonnes, String label) { this.maxTonnes = maxTonnes; this.label = label; }

        public static TruckClass forWeight(double tonnes) {
            for (TruckClass c : values()) if (tonnes <= c.maxTonnes) return c;
            return SUPER_30T;
        }
        /** A truck can take a load of its own class or one class below (not worth a 30 t run for 1 t). */
        public boolean canCarry(TruckClass required) {
            return ordinal() == required.ordinal() || ordinal() == required.ordinal() + 1;
        }
    }

    public enum BodyType { OPEN, CLOSED_BOX, FLATBED, TIPPER, TAUTLINER, REFRIGERATED, TANKER, LOWBED }

    public enum LoadStatus {
        OPEN, ASSIGNED, EN_ROUTE_PICKUP, AT_PICKUP, LOADED, IN_TRANSIT, DELIVERED, COMPLETED, CANCELLED;
        public boolean isActiveTrip() {
            return this == ASSIGNED || this == EN_ROUTE_PICKUP || this == AT_PICKUP || this == LOADED || this == IN_TRANSIT;
        }
    }

    public enum OfferStatus { PENDING, ACCEPTED, REJECTED, WITHDRAWN, EXPIRED }
    public enum PaymentMethod { PAYNOW_WEB, ECOCASH, ONEMONEY, INNBUCKS, CASH }
    public enum PaymentStatus { CREATED, SENT, PAID, AWAITING_DELIVERY, DELIVERED, CANCELLED, FAILED, REFUNDED, DISPUTED }
    public enum MsgType { TEXT, IMAGE, SYSTEM, CALL_LOG }
}
