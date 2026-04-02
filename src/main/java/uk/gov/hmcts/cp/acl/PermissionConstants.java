package uk.gov.hmcts.cp.acl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class PermissionConstants {
    private static final String OBJECT_STR = "object";
    private static final String OBJECT = "Court List";

    private static final String ACTION_STR = "action";
    private static final String ACTION = "Publish";

    private PermissionConstants() {
    }

    public static String[] accessToPublishCourtListPermissions() {
        ObjectNode courtListPermissionObj = new ObjectMapper().createObjectNode();
        courtListPermissionObj.put(OBJECT_STR, OBJECT);
        courtListPermissionObj.put(ACTION_STR, ACTION);
        return new String[]{courtListPermissionObj.toString()};
    }

}
