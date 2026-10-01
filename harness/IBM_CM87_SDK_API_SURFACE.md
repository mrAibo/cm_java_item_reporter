# IBM Content Manager 8.7 SDK — Authoritative API Surface Report

**Source (authoritative, read-only):** `C:\Users\crown\Downloads\Projects\CM_Migrator\lib\cmbicmsdk81.jar`
(9,562,412 bytes, 1102 entries, 868 `.class` files, modified 2026-02-04)
**Toolchain:** `C:\Users\crown\Downloads\Projects\CM_Itemtype_java\.tools\jdk17\bin\javap.exe` (Temurin 17.0.20.1+1)
**Method:** JAR extracted read-only to `C:\Users\crown\AppData\Local\Temp\ibmsdk\classes`; every signature below is copied from real `javap` output. No file in any project repository was modified.
**Internal release string found in bytecode:** `DK_ICM_RELEASE_VERSION = "8.7.0.000"`, `DK_ICM_API_VERSION = "0807000000"`.

---

## 0. PACKAGE CORRECTIONS — READ FIRST (these will break the build)

Four of the classes named in the request are **not** where the request assumed. Verified by `jar tf` and `javap`:

| Assumed FQCN | **Actual FQCN** | Why |
|---|---|---|
| `com.ibm.mm.sdk.common.DKDatastoreICM` | **`com.ibm.mm.sdk.server.DKDatastoreICM`** | Lives in the `server` package, not `common`. |
| `com.ibm.mm.sdk.common.DKSystemException` | **`com.ibm.mm.sdk.common.DKSystemError`** | No `DKSystemException` exists anywhere in the JAR. The class is `DKSystemError`. |
| `com.ibm.mm.sdk.common.DKConstant` | `com.ibm.mm.sdk.common.DKConstant` ✔ | Correct — but it is an **`interface`**, not a class. |
| `com.ibm.mm.sdk.common.DKConstantICM` | `com.ibm.mm.sdk.common.DKConstantICM` ✔ | Correct — also an **`interface`**, `extends DKConstant`. |

Additional compile-breaking facts:

1. **There is no `close()` method.** `javap` over the whole JAR shows zero `close(...)` declarations on `DKDatastoreICM`, `dkDatastore`, `dkDatastoreExt`, `dkAbstractDatastore`, `DKDatastoreDefICM`, `DKDatastoreAdminICM`, or `dkQueryManager`. The teardown pair is **`disconnect()`** (logoff, reusable) and **`destroy()`** (releases the object). A stub must **not** declare `close()`, and adapter code must not call it.
2. **`datastoreDef()` does NOT narrow its return type.** It returns `com.ibm.mm.sdk.common.dkDatastoreDef` both on the interface and on the `DKDatastoreICM` override. A cast to `DKDatastoreDefICM` is required to reach ICM-only methods.
3. **`datastoreAdmin()` does NOT narrow either.** It returns `com.ibm.mm.sdk.common.dkDatastoreAdmin`, which has **no `policyMgmt()`**. A cast to `DKDatastoreAdminICM` is required.
4. **`DKDatastoreICM.isConnected()` declares no `throws`**, while the interface/base declaration is `isConnected() throws java.lang.Exception`. Calling through the *concrete* type therefore needs no try/catch; calling through `dkDatastore` does.
5. **Retention/expiration/versioning "constants" are Java `enum`s, not `int`/`short` constants.** `DK_ICM_RETENTION_TYPE`, `DK_ICM_POLICY_TIME_UNIT`, `DK_ICM_EXPIRATION_ACTION_TYPE` are nested **enums**. Stubs must declare them as enums (`extends java.lang.Enum`), not as `static final int`.
6. **`DKSystemError` is the sibling of `DKUsageError`**, both `extends DKException`. Common error/exception types: `DKDatastoreAccessError`, `DKDatastoreError`, `DKSystemError`, `DKUsageError`, `DKXDOAccessError`, `DKXDOError`, `DKAlreadyCheckedOutException`, `DKAlreadyExistException`, `DKDataTypeMismatchException`, `DKExitExceptionICM`, `DKInvalidParameterException`, `DKInvalidPasswordException`, `DKLogonPromptException`, `DKNotCheckedOutException`, `DKNotExistException`, `DKPasswordExpiredException`, `DKQueryException`, `DKSizeOutOfBoundsException`, `DKXMLException`.

---

## 1. Class-file version and access flags

`javap -verbose` on one class reports:

```
  minor version: 0
  major version: 52
```

**major version 52 = Java 8 bytecode.** Compiling against it with the JDK 17 toolchain at `-source/-target 17` (or `--release 17`) is fine. Every class below reports `flags: (0x0021) ACC_PUBLIC, ACC_SUPER`; the two constant holders report `flags: (0x0601) ACC_PUBLIC, ACC_INTERFACE, ACC_ABSTRACT`.

| FQCN | major | flags | final? |
|---|---|---|---|
| `com.ibm.mm.sdk.server.DKDatastoreICM` | 52 | `ACC_PUBLIC, ACC_SUPER` | no |
| `com.ibm.mm.sdk.common.DKDatastoreDefICM` | 52 | `ACC_PUBLIC, ACC_SUPER` | no |
| `com.ibm.mm.sdk.common.DKDatastoreAdminICM` | 52 | `ACC_PUBLIC, ACC_SUPER` | no |
| `com.ibm.mm.sdk.common.DKItemTypeDefICM` | 52 | `ACC_PUBLIC, ACC_SUPER` | no |
| `com.ibm.mm.sdk.common.DKPolicyMgmtICM` | 52 | `ACC_PUBLIC, ACC_SUPER` | no |
| `com.ibm.mm.sdk.common.DKRetentionPolicyDefICM` | 52 | `ACC_PUBLIC, ACC_SUPER` | no |
| `com.ibm.mm.sdk.common.DKRetrieveOptionsICM` | 52 | `ACC_PUBLIC, ACC_SUPER` | no |
| `com.ibm.mm.sdk.common.DKConstantICM` | 52 | `ACC_PUBLIC, ACC_INTERFACE, ACC_ABSTRACT` | n/a (interface) |
| `com.ibm.mm.sdk.common.DKConstant` | 52 | `ACC_PUBLIC, ACC_INTERFACE, ACC_ABSTRACT` | n/a (interface) |

All ten are `public`. The JAR also contains `java_cup/runtime/*` classes; unrelated to CM.

---

## 2. `com.ibm.mm.sdk.server.DKDatastoreICM`

**Hierarchy:** `DKDatastoreICM extends com.ibm.mm.sdk.server.dkAbstractDatastore implements com.ibm.mm.sdk.common.dkDatastoreIntICM`
**Full chain:** `→ dkAbstractDatastore implements dkDatastore, DKConstant, DKMessageId` → `dkDatastore extends dkQueryManager` → `dkQueryManager extends dkQueryEvaluator`

Verified declaration:
```
public class com.ibm.mm.sdk.server.DKDatastoreICM extends com.ibm.mm.sdk.server.dkAbstractDatastore implements com.ibm.mm.sdk.common.dkDatastoreIntICM
```

### Relevant javap lines (trimmed)

```
public com.ibm.mm.sdk.server.DKDatastoreICM() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.server.DKDatastoreICM(java.lang.String) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public void connect(java.lang.String, java.lang.String, java.lang.String, java.lang.String) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public void disconnect() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public void destroy() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public boolean isConnected();
public com.ibm.mm.sdk.common.dkDatastoreDef datastoreDef() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public void commit() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public void rollback() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public void startTransaction() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public static java.lang.String getAPIVer();
public static java.lang.String getAPILSVer();
public com.ibm.mm.sdk.common.DKDDO createDDO(java.lang.String, int) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.dkResultSetCursor execute(java.lang.String, short, com.ibm.mm.sdk.common.DKNVPair[]) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.dkCollection listDataSources() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public java.lang.String[] listDataSourceNames() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public java.lang.String userName() throws java.lang.Exception;
public java.lang.String datastoreName() throws java.lang.Exception;
public java.lang.String datastoreType() throws java.lang.Exception;
public short validateConnection() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.dkCollection listEntities() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public java.lang.String[] listEntityNames() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.DKHandle connection() throws java.lang.Exception;
public short getDBType();
public java.lang.String schemaName() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public java.lang.String languageCode() throws java.lang.Exception;
public java.lang.String getAPIVersion();
public java.lang.String getLSVersion();
public java.lang.String getSessionId();
public java.lang.String getLSSessionId();
public java.lang.String getLogID();
public java.lang.String getUserName();
public void clearCache() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public void clearCache(int) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.DKTimestamp lastLogonTimestamp() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public short numberOfFailedLogons() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
```

Inherited from `dkAbstractDatastore` (public, relevant):
```
public void connectWithCredential(java.lang.String, com.ibm.mm.sdk.common.DKAuthenticationData, java.lang.String, java.lang.String, java.lang.String) throws com.ibm.mm.sdk.common.DKLogonFailure, com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public void connectWithCredential(java.lang.String, com.ibm.mm.sdk.common.DKAuthenticationData, java.lang.String) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public void connect(java.lang.String, java.lang.String, java.lang.String, java.lang.String) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public void disconnect() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public void destroy() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public boolean isConnected() throws java.lang.Exception;
public void commit() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public void rollback() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public void startTransaction() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.dkDatastoreDef datastoreDef() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public void setOption(int, java.lang.Object) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public java.lang.Object getOption(int) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
```

### Method table

| Method | Exact signature | Notes |
|---|---|---|
| ctor | `public DKDatastoreICM() throws DKException, java.lang.Exception` | Throws at construction — must be inside try/catch. Not static/final. |
| ctor | `public DKDatastoreICM(java.lang.String) throws DKException, java.lang.Exception` | `String` = datastore name. |
| connect | `public void connect(java.lang.String, java.lang.String, java.lang.String, java.lang.String) throws DKException, java.lang.Exception` | **The ONLY `connect` overload on this class.** Params are `(datastoreName, userName, password, optionsString)` per IBM convention — parameter *names* are not in the bytecode, so verify against IBM docs before relying on order. |
| connect (static helper) | `public static DKDatastoreICM connect(java.lang.String, java.lang.String, java.lang.String, java.lang.String) throws DKException, Exception` | **NOT PRESENT in this JAR.** Do not stub it. The documented `DKDatastoreICM.connect(...)` factory seen in some IBM samples is absent from 8.7 bytecode. |
| connectWithCredential | `public void connectWithCredential(String, DKAuthenticationData, String, String, String) throws DKLogonFailure, DKException, Exception` | Inherited from `dkAbstractDatastore`. 5-arg form. |
| connectWithCredential | `public void connectWithCredential(String, DKAuthenticationData, String) throws DKException, Exception` | 3-arg form; note it does **not** declare `DKLogonFailure`. |
| disconnect | `public void disconnect() throws DKException, java.lang.Exception` | The real teardown call. |
| destroy | `public void destroy() throws DKException, java.lang.Exception` | Releases the datastore object. |
| isConnected | `public boolean isConnected()` — **no throws** | Concrete override drops `throws Exception`. |
| datastoreDef | `public dkDatastoreDef datastoreDef() throws DKException, java.lang.Exception` | Returns the **base interface**. Cast to `DKDatastoreDefICM` needed. |
| commit | `public void commit() throws DKException, java.lang.Exception` | Mutating. |
| close | **DOES NOT EXIST** | See §0.1. |
| startTransaction / rollback | `public void startTransaction()` / `public void rollback() throws DKException, Exception` | rollback mutating (transaction state). |
| getAPIVer / getAPILSVer | `public static java.lang.String getAPIVer()` / `getAPILSVer()` | Static, no throws. Both are on `DKDatastoreICM` itself. |
| getAPIVersion / getLSVersion | `public java.lang.String getAPIVersion()` / `getLSVersion()` | Instance, no throws. Distinct from the two static ones above — easy to confuse. |

---

## 3. `com.ibm.mm.sdk.common.DKDatastoreDefICM`

**Hierarchy:** `DKDatastoreDefICM extends com.ibm.mm.sdk.common.dkAbstractDatastoreDef implements java.io.Serializable`
`dkAbstractDatastoreDef implements dkDatastoreDef, DKMessageId, Serializable`

```
public class com.ibm.mm.sdk.common.DKDatastoreDefICM extends com.ibm.mm.sdk.common.dkAbstractDatastoreDef implements java.io.Serializable
```

### Relevant javap lines (trimmed)

```
public static final int DK_ICM_USER_ITEM_TYPES = 1;
public static final int DK_ICM_ALL_ITEM_TYPES = 2;
public static final int DK_ICM_SYSTEM_ITEM_TYPES = 3;
public static final int DK_ICM_PARTS_ITEM_TYPES = 4;
public com.ibm.mm.sdk.common.DKDatastoreDefICM(com.ibm.mm.sdk.common.dkDatastore);
public com.ibm.mm.sdk.common.dkDatastoreAdmin datastoreAdmin() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.dkEntityDef createEntity() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.dkEntityDef createItemType() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.dkEntityDef retrieveEntity(java.lang.String) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.dkEntityDef retrieveEntity(int) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.dkCollection listEntities(int) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.dkCollection listEntities() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.dkCollection listEntities(com.ibm.mm.sdk.common.DKNVPair[]) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public java.lang.String[] listEntityNames(int) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public java.lang.String[] listEntityNames() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.DKItemTypeDefICM retrieveItemTypeView(java.lang.String) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public int getEntityIdByName(java.lang.String) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public java.lang.String getEntityNameById(int) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.dkCollection listItemTypeRelations() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.dkCollection retrieveItemTypeRelations(int) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.DKItemTypeRelationDefICM retrieveItemTypeRelation(int, int) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public java.lang.String[] listItemTypesWithAutoLinkEnabled() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public static java.lang.String getLanguageCode(java.util.Locale);
public static java.util.Locale getLocaleFromLanguageCode(java.lang.String);
public com.ibm.mm.sdk.common.dkCollection listActiveItemTypeViews() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.dkCollection listActiveItemTypeViews(int) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.DKItemTypeViewDefICM getActiveItemTypeView(java.lang.String) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public void clearCache() throws java.lang.Exception;
```

### Method table

| Method | Exact signature | Notes |
|---|---|---|
| ctor | `public DKDatastoreDefICM(dkDatastore)` | **Only** constructor; no no-arg ctor. Does not declare throws. |
| datastoreAdmin | `public dkDatastoreAdmin datastoreAdmin() throws DKException, java.lang.Exception` | Returns **`dkDatastoreAdmin`** (interface). Declared here but implemented in `dkAbstractDatastoreDef`. **Must cast** to `DKDatastoreAdminICM` for `policyMgmt()`. |
| listEntities | `public dkCollection listEntities(int) throws DKException, java.lang.Exception` | `int` = item-type scope, pass `DKDatastoreDefICM.DK_ICM_USER_ITEM_TYPES` (1) for user ItemTypes. |
| listEntities | `public dkCollection listEntities() throws DKException, java.lang.Exception` | All entities. |
| listEntities | `public dkCollection listEntities(DKNVPair[]) throws DKException, java.lang.Exception` | NVPair filter form. |
| retrieveEntity | `public dkEntityDef retrieveEntity(java.lang.String) throws DKException, java.lang.Exception` | By name. Returns **interface** `dkEntityDef`; cast to `DKItemTypeDefICM`. |
| retrieveEntity | `public dkEntityDef retrieveEntity(int) throws DKException, java.lang.Exception` | By id. Same cast requirement. |
| listEntityNames | `public String[] listEntityNames(int)` / `listEntityNames() throws DKException, java.lang.Exception` | Fastest listing path (no object materialisation). |
| getEntityIdByName | `public int getEntityIdByName(java.lang.String) throws DKException, java.lang.Exception` | Name → `int` id. |
| getEntityNameById | `public java.lang.String getEntityNameById(int) throws DKException, java.lang.Exception` | `int` id → name. |
| createEntity / createItemType | `public dkEntityDef createEntity()` / `createItemType() throws DKException, Exception` | Local object factory (not a server write by itself) — but paired with `add()` it becomes mutating. |

**Base-interface note:** `dkDatastoreDef` already declares `listEntities(int)`, `listEntities(DKNVPair[])`, `listEntityNames(int)`, `retrieveEntity(String)`, and `datastoreAdmin()`. So those five compile **without** a cast. `createItemType()`, `listItemTypesWithAutoLinkEnabled()`, `getEntityIdByName(int)`, and all XDO/MimeType/attr-group methods are ICM-only and **do** require casting `datastoreDef()` to `DKDatastoreDefICM`.

---

## 4. `com.ibm.mm.sdk.common.DKDatastoreAdminICM`

**Hierarchy:** `DKDatastoreAdminICM extends com.ibm.mm.sdk.common.dkAbstractDatastoreAdmin`

```
public class com.ibm.mm.sdk.common.DKDatastoreAdminICM extends com.ibm.mm.sdk.common.dkAbstractDatastoreAdmin
```

### Relevant javap lines (trimmed)

```
public com.ibm.mm.sdk.common.DKDatastoreAdminICM(com.ibm.mm.sdk.common.dkDatastore);
public com.ibm.mm.sdk.common.DKPolicyMgmtICM policyMgmt() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.dkAuthorizationMgmt authorizationMgmt() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.dkUserManagement userManagement() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.dkConfigurationMgmt configurationManagement() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.dkAdminDomainsMgmt adminDomainsMgmt() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.DKMimeTypeMgmtICM mimeTypeMgmt() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public void setDatastore(com.ibm.mm.sdk.common.dkDatastore);
public java.lang.String getConnectionUserID() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.DKLSQuiesceStatusICM quiesceStatus() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public void clearCache() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
```

### Method table

| Method | Exact signature | Notes |
|---|---|---|
| ctor | `public DKDatastoreAdminICM(dkDatastore)` | No throws. Normally not called directly — obtained via `datastoreAdmin()` + cast. |
| policyMgmt | `public DKPolicyMgmtICM policyMgmt() throws DKException, java.lang.Exception` | **The entry point for retention policies.** Does not exist on `dkDatastoreAdmin`. |
| authorizationMgmt | `public dkAuthorizationMgmt authorizationMgmt() throws DKException, Exception` | |
| userManagement | `public dkUserManagement userManagement() throws DKException, Exception` | |
| configurationManagement | `public dkConfigurationMgmt configurationManagement() throws DKException, Exception` | |
| adminDomainsMgmt | `public dkAdminDomainsMgmt adminDomainsMgmt() throws DKException, Exception` | |
| mimeTypeMgmt | `public DKMimeTypeMgmtICM mimeTypeMgmt() throws DKException, Exception` | |
| setDatastore | `public void setDatastore(dkDatastore)` | Mutating (rebinds admin to a datastore); no throws. |
| getConnectionUserID | `public java.lang.String getConnectionUserID() throws DKException, Exception` | Read-only. |
| quiesceStatus | `public DKLSQuiesceStatusICM quiesceStatus() throws DKException, Exception` | Read-only. |

**Working call chain (compiles):**
```java
DKDatastoreICM ds = new DKDatastoreICM();
ds.connect(name, user, pwd, options);
DKDatastoreDefICM dsDef = (DKDatastoreDefICM) ds.datastoreDef();       // cast required
DKDatastoreAdminICM admin = (DKDatastoreAdminICM) dsDef.datastoreAdmin(); // cast required
DKPolicyMgmtICM pol = admin.policyMgmt();
String[] names = pol.listRetentionPolicyNames();
ds.disconnect();
```

---

## 5. `com.ibm.mm.sdk.common.DKPolicyMgmtICM`

Full class is only 19 javap lines — reproduced almost entirely.

```
public class com.ibm.mm.sdk.common.DKPolicyMgmtICM
  public com.ibm.mm.sdk.common.DKPolicyMgmtICM(com.ibm.mm.sdk.common.dkDatastore) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
  public void add(com.ibm.mm.sdk.common.DKRetentionPolicyDefICM) throws com.ibm.mm.sdk.common.DKAlreadyExistException, com.ibm.mm.sdk.common.DKException, java.lang.Exception;
  public void del(com.ibm.mm.sdk.common.DKRetentionPolicyDefICM) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
  public void del(int) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
  public void del(java.lang.String) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
  public com.ibm.mm.sdk.common.dkCollection listRetentionPolicies() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
  public java.lang.String[] listRetentionPolicyNames() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
  public java.lang.String[] listItemTypeNamesByRetentionPolicy(java.lang.String) throws com.ibm.mm.sdk.common.DKNotExistException, com.ibm.mm.sdk.common.DKException, java.lang.Exception;
  public com.ibm.mm.sdk.common.dkCollection listItemTypesByRetentionPolicy(java.lang.String) throws com.ibm.mm.sdk.common.DKNotExistException, com.ibm.mm.sdk.common.DKException, java.lang.Exception;
  public com.ibm.mm.sdk.common.DKRetentionPolicyDefICM retrieveRetentionPolicy(int) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
  public com.ibm.mm.sdk.common.DKRetentionPolicyDefICM retrieveRetentionPolicy(java.lang.String) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
  public void update(com.ibm.mm.sdk.common.DKRetentionPolicyDefICM) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
  public void clearCache() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
```

| Method | Exact signature | Notes |
|---|---|---|
| ctor | `public DKPolicyMgmtICM(dkDatastore) throws DKException, java.lang.Exception` | Public. |
| listRetentionPolicyNames | `public java.lang.String[] listRetentionPolicyNames() throws DKException, java.lang.Exception` | No args. Returns `String[]`. |
| listRetentionPolicies | `public dkCollection listRetentionPolicies() throws DKException, java.lang.Exception` | Returns `dkCollection` of `DKRetentionPolicyDefICM`. |
| retrieveRetentionPolicy | `public DKRetentionPolicyDefICM retrieveRetentionPolicy(int) throws DKException, java.lang.Exception` | By numeric id. |
| retrieveRetentionPolicy | `public DKRetentionPolicyDefICM retrieveRetentionPolicy(java.lang.String) throws DKException, java.lang.Exception` | By policy name. |
| listItemTypeNamesByRetentionPolicy | `public java.lang.String[] listItemTypeNamesByRetentionPolicy(java.lang.String) throws DKNotExistException, DKException, java.lang.Exception` | **Three** throws, `DKNotExistException` first. |
| listItemTypesByRetentionPolicy | `public dkCollection listItemTypesByRetentionPolicy(java.lang.String) throws DKNotExistException, DKException, Exception` | Collection twin. |
| add | `public void add(DKRetentionPolicyDefICM) throws DKAlreadyExistException, DKException, Exception` | Mutating. |
| update | `public void update(DKRetentionPolicyDefICM) throws DKException, Exception` | Mutating. |
| del | `public void del(DKRetentionPolicyDefICM)` / `del(int)` / `del(String) throws DKException, Exception` | Mutating, three overloads. |
| clearCache | `public void clearCache() throws DKException, Exception` | Client-side cache only. |

---

## 6. `com.ibm.mm.sdk.common.DKRetentionPolicyDefICM`

```
public class com.ibm.mm.sdk.common.DKRetentionPolicyDefICM implements com.ibm.mm.sdk.common.DKMessageIdICM
```

### Complete getter/setter javap lines

```
public com.ibm.mm.sdk.common.DKRetentionPolicyDefICM();
public com.ibm.mm.sdk.common.DKRetentionPolicyDefICM(java.lang.String);
public com.ibm.mm.sdk.common.DKRetentionPolicyDefICM(com.ibm.mm.sdk.common.DKRetentionPolicyDefICM);
public int getID();
public void setID(int);
public java.lang.String getName();
public void setName(java.lang.String);
public java.lang.String getDescription();
public void setDescription(java.lang.String);
public com.ibm.mm.sdk.common.DKRetentionPolicyDefICM$DK_ICM_RETENTION_TYPE getRetentionType();
public void setRetentionType(com.ibm.mm.sdk.common.DKRetentionPolicyDefICM$DK_ICM_RETENTION_TYPE);
public com.ibm.mm.sdk.common.DKRetentionPolicyDefICM$DK_ICM_POLICY_TIME_UNIT getDefaultRetentionTimeUnit();
public void setDefaultRetentionTimeUnit(com.ibm.mm.sdk.common.DKRetentionPolicyDefICM$DK_ICM_POLICY_TIME_UNIT);
public void setRetentionEnabled(boolean);
public boolean isRetentionEnabled();
public int getRetentionTimePeriod();
public void setRetentionTimePeriod(int) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public void setExpirationEnabled(boolean);
public boolean isExpirationEnabled();
public com.ibm.mm.sdk.common.DKRetentionPolicyDefICM$DK_ICM_POLICY_TIME_UNIT getDefaultExpirationTimeUnit();
public void setDefaultExpirationTimeUnit(com.ibm.mm.sdk.common.DKRetentionPolicyDefICM$DK_ICM_POLICY_TIME_UNIT);
public int getExpirationTimePeriod();
public void setExpirationTimePeriod(int) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.DKRetentionPolicyDefICM$DK_ICM_EXPIRATION_ACTION_TYPE getExpirationAction();
public void setExpirationAction(com.ibm.mm.sdk.common.DKRetentionPolicyDefICM$DK_ICM_EXPIRATION_ACTION_TYPE);
public void setDeleteExpiredItemsForceCheckInEnabled(boolean);
public boolean isDeleteExpiredItemsForceCheckInEnabled();
public void setDeleteExpiredItemsMaximumDuration(int);
public int getDeleteExpiredItemsMaximumDuration();
public void setDeleteExpiredItemsMaximumRows(int);
public int getDeleteExpiredItemsMaximumRows();
public void setDeleteExpiredItemsCommitCount(int);
public int getDeleteExpiredItemsCommitCount();
public void setDeleteExpiredItemsScheduleInformation(java.lang.String);
public java.lang.String getDeleteExpiredItemsScheduleInformation();
```

### EVERY public getter, with exact return type

| Requested concept | **Exact getter name** | Return type | Notes |
|---|---|---|---|
| policy name | `getName()` | `java.lang.String` | |
| policy id | `getID()` | `int` | Capital `ID`. |
| description | `getDescription()` | `java.lang.String` | |
| retention type | `getRetentionType()` | `DK_ICM_RETENTION_TYPE` (**enum**) | Not `int`. |
| retention enabled | `isRetentionEnabled()` | `boolean` | `is` prefix, not `get`. |
| retention period | `getRetentionTimePeriod()` | **`int`** | Not `short`. |
| retention unit | `getDefaultRetentionTimeUnit()` | `DK_ICM_POLICY_TIME_UNIT` (**enum**) | Name is `Default…TimeUnit`, **not** `getRetentionUnit()`. |
| expiration enabled | `isExpirationEnabled()` | `boolean` | `is` prefix. |
| expiration period | `getExpirationTimePeriod()` | **`int`** | |
| expiration unit | `getDefaultExpirationTimeUnit()` | `DK_ICM_POLICY_TIME_UNIT` (**enum**) | `Default…TimeUnit`. |
| expiration action | `getExpirationAction()` | `DK_ICM_EXPIRATION_ACTION_TYPE` (**enum**) | |
| auto-delete schedule | `getDeleteExpiredItemsScheduleInformation()` | **`java.lang.String`** | Full name is long; no `getSchedule()`. |
| commit count | `getDeleteExpiredItemsCommitCount()` | **`int`** | |
| max rows / max items | `getDeleteExpiredItemsMaximumRows()` | **`int`** | "max items" == this method. |
| max duration | `getDeleteExpiredItemsMaximumDuration()` | **`int`** | |
| force check-in | `isDeleteExpiredItemsForceCheckInEnabled()` | `boolean` | `is` prefix. |

**No `short` return types exist on this class.** Every numeric getter is `int`; every flag is `boolean`. Setters for `setRetentionTimePeriod(int)` and `setExpirationTimePeriod(int)` throw; the other setters do not.

---

## 7. `com.ibm.mm.sdk.common.DKItemTypeDefICM`

**Hierarchy:** `DKItemTypeDefICM extends DKComponentTypeDefICM implements java.io.Serializable`
`DKComponentTypeDefICM extends dkAbstractEntityDef implements java.io.Serializable`
`dkAbstractEntityDef implements dkEntityDef, DKMessageId, java.io.Serializable`

**Sibling item-type definition classes in the same package** (all present):
`DKComponentTypeDefICM`, `DKItemTypeViewDefICM`, `DKComponentTypeViewDefICM`, `DKItemTypeRelationDefICM`, `DKComponentTypeAttrGroupDefICM`, `DKComponentTypeIndexDefICM`, `DKAttrDefICM`, `DKAttrGroupDefICM`, `DKLinkTypeDefICM`, `DKXDOClassificationDefICM`, `DKResourceMgrDefICM`, `DKSMSCollectionDefICM`, `DKAdminDomainDefICM`.

### Property getters — exact javap lines

```
public com.ibm.mm.sdk.common.DKItemTypeDefICM();
public com.ibm.mm.sdk.common.DKItemTypeDefICM(com.ibm.mm.sdk.common.dkDatastore);
public com.ibm.mm.sdk.common.DKItemTypeDefICM(com.ibm.mm.sdk.common.DKItemTypeDefICM);
public java.lang.String getName();                                  // inherited: dkAbstractEntityDef
public java.lang.String getDescription();                           // inherited: dkAbstractEntityDef
public short getId();                                               // inherited: dkAbstractEntityDef / DKComponentTypeDefICM
public int getIntId();                                              // declared on DKItemTypeDefICM
public short getClassification();
public short getDefaultRMCode();
public short getDefaultCollCode();
public short getDefaultPrefchCollCode();
public short getDefaultRetentionUnit();
public int getDefaultItemRetention();
public int getXDOClassID();
public java.lang.String getXDOClassName();
public java.lang.String getJavaXDOClassName();
public short getVersionControl();
public short getVersionMax();
public short getVersioningType();
public int getItemTypeRetentionPolicyId() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public java.lang.String getItemTypeRetentionPolicyName() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public int getItemTypeACLCode() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public java.lang.String getItemTypeACLName() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public boolean isRoot();
public boolean isRecordsEnabled();
public boolean isP8RecordsEnabled();
public boolean isIBMEnterpriseRecordsEnabled();
public boolean isHierarchical();
public boolean foldersOnly();
public boolean foldersNotAllowed();
public int getItemTypeFlag();
public boolean getParentFolderACLInheritanceEnabled();
public short getItemLevelACLFlag();
public short getDefaultACLChoice();
public short getAutoLinkSMS();
public boolean getAutoLinkEnable();
public short getItemEventFlag();
public boolean isTextSearchable();
public boolean isItemTypeModified();
public com.ibm.mm.sdk.common.dkCollection getAutoLinkRule();
public com.ibm.mm.sdk.common.dkCollection getItemTypeRelations() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public java.util.Vector getItemTypeViewFromMemory() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public com.ibm.mm.sdk.common.dkCollection listItemTypeViews() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public java.lang.String[] listItemTypeViewNames() throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public java.lang.String[] listItemTypeViewNames(int) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public java.lang.String getProcessName();
public int getDefaultPriority();
public int getPartIDByName(java.lang.String) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public java.lang.String getPartNameByID(int) throws com.ibm.mm.sdk.common.DKException, java.lang.Exception;
public int getDeleteExpiredItemsMaximumDuration();
public int getDeleteExpiredItemsMaximumRows();
public int getDeleteExpiredItemsCommitCount();
public java.lang.String getDeleteExpiredItemsScheduleInformation();
public com.ibm.mm.sdk.common.DKItemTypeDefICM$DK_ICM_ITEMTYPE_DELETE_EXPIRED_ITEMS_SCHEDULER_TYPE getDeleteExpiredItemsSchedulerType();
public com.ibm.mm.sdk.common.DKItemTypeDefICM$DK_ICM_ITEMTYPE_REINDEX_DEFAULT_ACL_TYPE getReindexDefaultACL();
public com.ibm.mm.sdk.common.DKItemTypeDefICM$DK_ICM_ITEMTYPE_REINDEX_ACL_CONTROL_MODE_TYPE getReindexACLControlModeType();
public long getEntityId();      // NOT PRESENT
public int getItemTypeId();     // NOT PRESENT
```

### Property table

| Requested concept | **Exact getter** | Return type | Notes |
|---|---|---|---|
| name | `getName()` | `String` | Inherited from `dkAbstractEntityDef`. No throws. |
| description | `getDescription()` | `String` | Inherited from `dkAbstractEntityDef`. No throws. (There is a separate `getDescription(String)` on `DKComponentTypeDefICM` that does throw.) |
| **id (the int id)** | **`getIntId()`** | **`int`** | **This is the method to use.** Declared directly on `DKItemTypeDefICM`. |
| id (legacy) | `getId()` | **`short`** | Inherited. **Lossy — do not use for the 32-bit item-type id.** |
| classification | `getClassification()` | **`short`** | Values `0..3`, see §8. |
| XDO class id | `getXDOClassID()` | **`int`** | Capital `ID`. |
| XDO class name | `getXDOClassName()` | `String` | |
| XDO java class name | `getJavaXDOClassName()` | `String` | Extra getter; often the JDO/Java class. |
| default RM | `getDefaultRMCode()` | **`short`** | "RM" = Resource Manager. |
| collection code | `getDefaultCollCode()` | **`short`** | |
| prefetch collection code | `getDefaultPrefchCollCode()` | `short` | Note the spelling `Prefch`. |
| version control | `getVersionControl()` | **`short`** | Values `0/1/2`, see §8. |
| versioning type | `getVersioningType()` | **`short`** | Values `0/1/2`, see §8. |
| version max | `getVersionMax()` | `short` | |
| retention policy name | `getItemTypeRetentionPolicyName()` | `String` | **throws** `DKException, Exception`. |
| retention policy id (legacy) | `getItemTypeRetentionPolicyId()` | **`int`** | **throws** `DKException, Exception`. |
| legacy retention unit | `getDefaultRetentionUnit()` | **`short`** | |
| legacy retention period | `getDefaultItemRetention()` | **`int`** | |
| item-level ACL flag | `getItemLevelACLFlag()` | `short` | |
| ACL code / name | `getItemTypeACLCode()` / `getItemTypeACLName()` | `int` / `String` | Both **throw**. |
| reindex default ACL | `getReindexDefaultACL()` | `DK_ICM_ITEMTYPE_REINDEX_DEFAULT_ACL_TYPE` (enum) | |
| reindex ACL control mode | `getReindexACLControlModeType()` | `DK_ICM_ITEMTYPE_REINDEX_ACL_CONTROL_MODE_TYPE` (enum) | |
| auto-delete schedule | `getDeleteExpiredItemsScheduleInformation()` | `String` | |
| auto-delete commit count | `getDeleteExpiredItemsCommitCount()` | `int` | |
| auto-delete max rows | `getDeleteExpiredItemsMaximumRows()` | `int` | |
| auto-delete max duration | `getDeleteExpiredItemsMaximumDuration()` | `int` | |
| auto-delete scheduler | `getDeleteExpiredItemsSchedulerType()` | `DK_ICM_ITEMTYPE_DELETE_EXPIRED_ITEMS_SCHEDULER_TYPE` (enum) | |
| records enabled | `isRecordsEnabled()` | `boolean` | |
| P8 records | `isP8RecordsEnabled()` | `boolean` | |
| IBM Enterprise Records | `isIBMEnterpriseRecordsEnabled()` | `boolean` | |
| hierarchical / folders | `isHierarchical()`, `foldersOnly()`, `foldersNotAllowed()` | `boolean` | No `is` prefix on the last two. |
| itemtype flag | `getItemTypeFlag()` | `int` | |

**Answers to "how is the integer ID obtained":** `getIntId()` → `int`. Neither `getEntityId()` nor `getItemTypeId()` exists on `DKItemTypeDefICM`, `dkAbstractEntityDef`, `dkEntityDef`, or `DKComponentTypeDefICM`. Related helpers on `DKDatastoreDefICM`: `getEntityIdByName(String)` → `int`, `getEntityNameById(int)` → `String`.

---

## 8. Constants

### 8.1 `com.ibm.mm.sdk.common.DKConstantICM` — `public interface ... extends DKConstant`

Compile-time constants are `static final` in an interface, so all are inlined at compile time.

| Constant | Type | Value |
|---|---|---|
| `DK_ICM_ENTITY_TYPE` | `java.lang.String` | `"ENTITY_TYPE"` |
| `DK_ICM_BASE` | `int` | `1` |
| `DK_ICM_BASE_AND_VIEW` | `int` | `3` |
| `DK_ICM_ITEMTYPE` | `short` | `1` |
| `DK_ICM_ITEMTYPEVIEW` | `short` | `2` |
| `ICM_NLS_ITEMTYPE_CLASS` | `short` | `2` |
| `ICM_NLS_ITCLASSIFICATION` | `short` | `7` |
| `ICM_NLS_ITEMTYPE_VIEW_CLASS` | `short` | `18` |
| `ICM_NLS_RETENTION_POLICY_CLASS` | `short` | `40` |
| `DK_ICM_RELEASE_VERSION` | `java.lang.String` | `"8.7.0.000"` |
| `DK_ICM_API_VERSION` | `java.lang.String` | `"0807000000"` |
| `DK_ICM_RELEASE_VERSION_LENGTH` | `int` | `128` |
| `DK_ICM_RELEASE_VERSION_LENGTH_WITHNULL` | `int` | `129` |
| `DK_ICM_RETENTION_FEATURE_NAME` | `java.lang.String` | `"CM Retention Management"` |
| `DK_ICM_SYSTEM_HIERARCHICAL_ITEMTYPE_NAME` | `java.lang.String` | `"ICM$FOLDER"` |
| `DK_ICM_SYSTEM_HIERARCHICAL_ITEMTYPE_ID` | `int` | `410` |
| `DK_ICM_PROPERTY_ITEMTYPE_ID` | `java.lang.String` | `"itemtype-id"` |
| `DK_ICM_PROPERTY_RETENTIONDATE` | `java.lang.String` | `"ICM$RETENTIONDATE"` |
| `DK_ICM_PROPERTY_EXPIRATIONDATE` | `java.lang.String` | `"SYSROOTATTRS.EXPIRATIONDATE"` |
| `DK_ICM_PROPERTY_GMT_RETENTIONDATE` | `java.lang.String` | `"ICMGMTRETENTIONDATE"` |
| `DK_ICM_PROPERTY_DOC_ITEMTYPE` | `java.lang.String` | `"ICMDOCITEMTYPE"` |
| `DK_ICM_ITEMTYPEFLAG_COLUMNNAME` | `java.lang.String` | `"ITEMTYPEFLAG"` |
| `DK_ICM_SYSATTR_VERSIONID` | `java.lang.String` | `"VERSIONID"` |
| `DK_ICM_PRIV_SYSTEM_DEFINE_ITEM_TYPE` | `java.lang.String` | `"SystemDefineItemType"` |
| `DK_ICM_PRIV_ITEM_TYPE_QUERY` | `java.lang.String` | `"ItemTypeQuery"` |
| `DK_ICM_DELETE_RULE_NO_ACTION` | `short` | `3` |
| `DK_ICM_ID_ITEMTYPE_CLASS_ID` | `short` | `56` |
| `DK_ICM_ID_VERSION_CONTROL` | `short` | `148` |
| `DK_ICM_ID_RETENTION_TYPE` | `short` | `278` |
| `DK_ICM_ID_RETENTION_ENABLED` | `short` | `279` |
| `DK_ICM_ID_EXPIRATION_ENABLED` | `short` | `280` |
| `DK_ICM_ID_EXPIRATION_ACTION` | `short` | `281` |
| `DK_ICM_ID_ITEMTYPEID_COL_NAME` | `short` | `168` |
| `DK_ICM_ID_VERSIONID_COL_NAME` | `short` | `169` |
| `DK_ICM_RTARGETITEMTYPEID` | `short` | `101` |
| `DK_ICM_RTARGETVERSIONID` | `short` | `115` |
| `DK_CM_OPT_RETENTION_POLICY_CACHE` | `int` | `79` |
| `DK_ICM_DELETE_ALL_VERSIONS` | `int` | `-1` |
| `DK_ICM_DESTROY_ALL_VERSIONS` | `int` | `131071` |
| `DK_ICM_SYSTEM_ATTR_RETENTION_DATE` | `int` | `131` |
| `DK_ICM_VERSION_82` | `java.lang.String` | `"8.2"` |
| `CMV82_RELEASE_VERSION` | `int` | `2` |

Representative return codes: `DK_ICM_RC_INVALID_ACTION=7102`, `DK_ICM_RC_ITEMTYPE_DELETE_ERROR=7350`, `DK_ICM_RC_ITEMTYPE_DUPLICATE_NAME=7351`, `DK_ICM_RC_ITEMTYPE_INSERT_ERROR=7352`, `DK_ICM_RC_ITEMTYPE_NOT_EMPTY=7357`, `DK_ICM_RC_ITEMTYPE_NOT_FOUND=7358`, `DK_ICM_RC_ITEMTYPE_UPDATE_ERROR=7359`, `DK_ICM_RC_RETENTION_EXPIRATION_NOT_ENABLED=7763`, `DK_ICM_RC_RETENTION_EXPIRATION_MAX_DELETE_ERRORS=7765`, `DK_ICM_EXPIRATION_DELETION_NOT_ENABLED=7770`, `DK_ICM_NOT_AUTH_FOR_ANY_ITEMTYPE=7917`.

### 8.2 `com.ibm.mm.sdk.common.DKConstant` — `public interface DKConstant`

| Constant | Type | Value |
|---|---|---|
| `DK_BASE` | `short` | `128` |
| `DK_CM_TRUE` | `int` | `1` |

`DKConstant` has **no** `DK_ICM_ENTITY_TYPE` and no `DK_RETENTION_*` / `DK_VERSION*` / `*CLASSIFICATION*` constants — those live only on `DKConstantICM` (above) or on the owning classes (below).

### 8.3 Classification values — `DKConstantICM` (all `public static final short`)

| Constant | Value | Meaning |
|---|---|---|
| `DK_ICM_ITEMTYPE_CLASS_ITEM` | `0` | Item |
| `DK_ICM_ITEMTYPE_CLASS_RESOURCE_ITEM` | `1` | Resource item |
| `DK_ICM_ITEMTYPE_CLASS_DOC_MODEL` | `2` | Document model |
| `DK_ICM_ITEMTYPE_CLASS_DOC_PART` | `3` | Document part |

Returned by `DKItemTypeDefICM.getClassification()` → `short`.

### 8.4 Version-control values — `DKConstantICM` (all `public static final int`)

| Constant | Value |
|---|---|
| `DK_ICM_VERSION_CONTROL_NEVER` | `0` |
| `DK_ICM_VERSION_CONTROL_ALWAYS` | `1` |
| `DK_ICM_VERSION_CONTROL_BY_APPLICATION` | `2` |

**Type mismatch warning:** these constants are `int`, but `DKItemTypeDefICM.getVersionControl()` returns **`short`**. Comparing/assigning directly needs a cast, e.g. `getVersionControl() == (short) DK_ICM_VERSION_CONTROL_ALWAYS`.

### 8.5 Versioning-type values — `DKConstantICM` (all `public static final short`)

| Constant | Value |
|---|---|
| `DK_ICM_DOC_NO_VERSIONING` | `0` |
| `DK_ICM_ITEM_VERSIONING_OPTIMIZED` | `1` |
| `DK_ICM_ITEM_VERSIONING_FULL` | `2` |

Returned by `DKItemTypeDefICM.getVersioningType()` → `short`. Types match (`short`/`short`).

### 8.6 Retention-type / period-unit / expiration-action values — **ENUMS**

`javap -constants` shows no numeric values because these are real Java enums.

`com.ibm.mm.sdk.common.DKRetentionPolicyDefICM$DK_ICM_RETENTION_TYPE`
```
public final class ...DK_ICM_RETENTION_TYPE extends java.lang.Enum<...DK_ICM_RETENTION_TYPE>
  public static final ...DK_ICM_RETENTION_TYPE FIXED_TIME;
  public static final ...DK_ICM_RETENTION_TYPE EVENT_DRIVEN;
  public static ...DK_ICM_RETENTION_TYPE[] values();
  public static ...DK_ICM_RETENTION_TYPE valueOf(java.lang.String);
```

`com.ibm.mm.sdk.common.DKRetentionPolicyDefICM$DK_ICM_POLICY_TIME_UNIT`
```
public final class ...DK_ICM_POLICY_TIME_UNIT extends java.lang.Enum<...DK_ICM_POLICY_TIME_UNIT>
  public static final ...DK_ICM_POLICY_TIME_UNIT DAY;
  public static final ...DK_ICM_POLICY_TIME_UNIT WEEK;
  public static final ...DK_ICM_POLICY_TIME_UNIT MONTH;
  public static final ...DK_ICM_POLICY_TIME_UNIT YEAR;
  public static ...DK_ICM_POLICY_TIME_UNIT[] values();
  public static ...DK_ICM_POLICY_TIME_UNIT valueOf(java.lang.String);
```

`com.ibm.mm.sdk.common.DKRetentionPolicyDefICM$DK_ICM_EXPIRATION_ACTION_TYPE`
```
public final class ...DK_ICM_EXPIRATION_ACTION_TYPE extends java.lang.Enum<...DK_ICM_EXPIRATION_ACTION_TYPE>
  public static final ...DK_ICM_EXPIRATION_ACTION_TYPE NO_ACTION;
  public static final ...DK_ICM_EXPIRATION_ACTION_TYPE AUTO_DELETE;
  public static ...DK_ICM_EXPIRATION_ACTION_TYPE[] values();
  public static ...DK_ICM_EXPIRATION_ACTION_TYPE valueOf(java.lang.String);
```

`com.ibm.mm.sdk.common.DKItemTypeDefICM$DK_ICM_ITEMTYPE_DELETE_EXPIRED_ITEMS_SCHEDULER_TYPE`
```
public final class ...DK_ICM_ITEMTYPE_DELETE_EXPIRED_ITEMS_SCHEDULER_TYPE extends java.lang.Enum<...>
  public static final ... POLICY;
  public static final ... ITEM_TYPE;
  public static ...[] values();
  public static ... valueOf(java.lang.String);
```

Also present as enums: `DKItemTypeDefICM$DK_ICM_ITEMTYPE_REINDEX_DEFAULT_ACL_TYPE`, `DKItemTypeDefICM$DK_ICM_ITEMTYPE_REINDEX_ACL_CONTROL_MODE_TYPE`.

### 8.7 Retention-unit constant

`DKItemTypeDefICM.DK_ICM_RETENTION_UNIT_YEAR` = `public static final short` = `0`.
`DKItemTypeDefICM.DK_ICM_ITEM_RETRENTION_NO_EXPIRE` = `public static final short` = `0` (note the typo `RETRENTION` preserved in the API).
`DKItemTypeDefICM.DK_ICM_ITEM_EVENT_CURD_DISABLED` = `public static final short` = `0`.
No `DK_ICM_RETENTION_UNIT_DAY/WEEK/MONTH` short constants exist — modern API uses the `DK_ICM_POLICY_TIME_UNIT` enum instead. `DKConstantICM.DK_ICM_DEFITEMTYPE2PARAM_ID_RETENTION_UNIT` (`short`, `229`) is a **parameter id**, not a unit value.

### 8.8 `DKDatastoreDefICM` own scope constants (all `public static final int`)

| Constant | Value |
|---|---|
| `DK_ICM_USER_ITEM_TYPES` | `1` |
| `DK_ICM_ALL_ITEM_TYPES` | `2` |
| `DK_ICM_SYSTEM_ITEM_TYPES` | `3` |
| `DK_ICM_PARTS_ITEM_TYPES` | `4` |

(`DKDatastoreDefICM` also holds private `DK_ICM_PARTS_ITEM_TYPES_START/_END` and `ACTION_UPDATE` — not usable in a stub's public surface.)

### 8.9 `DKItemTypeDefICM` own constants (all `public static final`)

| Constant | Type | Value |
|---|---|---|
| `DK_ICM_IT_APP_OPTION_BIT_0` | `short` | `0` |
| `DK_ICM_IT_APP_OPTION_BIT_1` | `short` | `1` |
| `DK_ICM_ITEMACL_BIND_AT_ITEMTYPE` | `short` | `0` |
| `DK_ICM_ITEMACL_BIND_AT_ITEM` | `short` | `1` |
| `DK_ICM_RETENTION_UNIT_YEAR` | `short` | `0` |
| `DK_ICM_ITEM_RETRENTION_NO_EXPIRE` | `short` | `0` |
| `DK_ICM_ITEM_EVENT_CURD_DISABLED` | `short` | `0` |
| `DK_ICM_ALL_VIEW_NAMES` | `int` | `1` |
| `DK_ICM_VIEW_NAMES_EXCEPT_BASE` | `int` | `2` |

(`DK_ICM_IT_FLAG_APP_OPTION_BIT_5/_6` are `private static final int` — not part of the public surface.)

---

## 9. Exceptions

```
public class com.ibm.mm.sdk.common.DKException extends com.ibm.mm.sdk.logtool.DKLogException implements com.ibm.mm.sdk.common.DKConstant, java.io.Serializable
public class com.ibm.mm.sdk.common.DKUsageError extends com.ibm.mm.sdk.common.DKException implements java.io.Serializable
public class com.ibm.mm.sdk.common.DKSystemError extends com.ibm.mm.sdk.common.DKException implements java.io.Serializable
```

| Class | Exact FQCN | Extends | Public ctors | Own methods |
|---|---|---|---|---|
| `DKException` | `com.ibm.mm.sdk.common.DKException` | `com.ibm.mm.sdk.logtool.DKLogException` | `()`, `(String)`, `(String,int)`, `(String,int,int)`, `(String,int,String,int)`, `(DKException)`, `(String,DKLogMessageInserts)`, `(String,int,DKLogMessageInserts)`, `(String,int,int,DKLogMessageInserts)`, `(String,int,String,int,DKLogMessageInserts)` | `String errorState()`, `int errorCode()`, `String name()`, `int getErrorId()`, `void setErrorId(int)`, `short exceptionId()` |
| `DKUsageError` | `com.ibm.mm.sdk.common.DKUsageError` | `DKException` | `()`, `(String)`, `(String,int)`, `(String,int,int)`, `(String,int,String,int)` + 4 `DKLogMessageInserts` variants | `String name()`, `short exceptionId()` |
| `DKSystemError` | `com.ibm.mm.sdk.common.DKSystemError` | `DKException` | same 10-ctor pattern as above | `String name()`, `short exceptionId()` |

Exact javap:
```
public com.ibm.mm.sdk.common.DKException();
public com.ibm.mm.sdk.common.DKException(java.lang.String);
public com.ibm.mm.sdk.common.DKException(java.lang.String, int);
public com.ibm.mm.sdk.common.DKException(java.lang.String, int, int);
public com.ibm.mm.sdk.common.DKException(java.lang.String, int, java.lang.String, int);
public com.ibm.mm.sdk.common.DKException(com.ibm.mm.sdk.common.DKException);
public java.lang.String errorState();
public int errorCode();
public java.lang.String name();
public int getErrorId();
public void setErrorId(int);
public short exceptionId();
public com.ibm.mm.sdk.common.DKUsageError();
public com.ibm.mm.sdk.common.DKUsageError(java.lang.String);
public com.ibm.mm.sdk.common.DKUsageError(java.lang.String, int);
public com.ibm.mm.sdk.common.DKUsageError(java.lang.String, int, int);
public com.ibm.mm.sdk.common.DKUsageError(java.lang.String, int, java.lang.String, int);
public com.ibm.mm.sdk.common.DKSystemError();
public com.ibm.mm.sdk.common.DKSystemError(java.lang.String);
public com.ibm.mm.sdk.common.DKSystemError(java.lang.String, int);
public com.ibm.mm.sdk.common.DKSystemError(java.lang.String, int, int);
public com.ibm.mm.sdk.common.DKSystemError(java.lang.String, int, java.lang.String, int);
```

**Stub note:** `DKException extends com.ibm.mm.sdk.logtool.DKLogException`, and several ctors take `com.ibm.mm.sdk.logtool.DKLogMessageInserts`. If the test stubs only cover `com.ibm.mm.sdk.common`, at least `com.ibm.mm.sdk.logtool.DKLogException` and `DKLogMessageInserts` must also be stubbed, or the `extends`/ctor signatures will not compile.

---

## 10. Mutating methods (for the read-only source guard)

These change server state. Names are grouped by owner; each entry is the exact method name with its parameter list.

### 10.1 `DKPolicyMgmtICM` — all mutating
- `add(DKRetentionPolicyDefICM)`
- `update(DKRetentionPolicyDefICM)`
- `del(DKRetentionPolicyDefICM)`
- `del(int)`
- `del(java.lang.String)`
- *(borderline)* `clearCache()`

### 10.2 `DKItemTypeDefICM` — all `set*`, all relation-mutating, plus text-index/auto-link mutations
**`set*` (34):** `setIntId(int)`, `setClassification(short)`, `setItemLevelACLFlag(short)`, `setItemTypeACLCode(int)`, `setItemTypeACLName(String)`, `setItemTypeRetentionPolicyId(int)`, `setItemTypeRetentionPolicyName(String)`, `setDefaultRMCode(short)`, `setDefaultCollCode(short)`, `setDefaultPrefchCollCode(short)`, `setDefaultRetentionUnit(short)`, `setDefaultItemRetention(int)`, `setXDOClassID(int)`, `setJavaXDOClassName(String)`, `setXDOClassName(String)`, `setAutoLinkEnable(boolean)`, `setAutoLinkSMS(short)`, `setVersionControl(short)`, `setVersionMax(short)`, `setItemEventFlag(short)`, `setTextSearchable(boolean)`, `setTextIndexDef(DKTextIndexDefICM)`, `setTextIndex(dkTextIndexICM)`, `setItemTypeView(dkCollection)`, `setVersioningType(short)`, `setAutoLinks(dkCollection)`, `setItemTypeModified(boolean)`, `setProcessName(String)`, `setDefaultPriority(int)`, `setItemTypeRelations(dkCollection)`, `setDefaultACLChoice(short)`, `setDatastore(dkDatastore)`, `setRecordsEnabled(boolean)`, `setP8RecordsEnabled(boolean)`, `setIBMEnterpriseRecordsEnabled(boolean)`, `setEventSubscriptionsEnabled(boolean)`, `setHierarchical(boolean)`, `setFoldersOnly(boolean)`, `setItemTypeFlag(int)`, `setApplicationOptionBit(short,boolean)`, `setParentFolderACLInheritanceEnabled(boolean)`, `setReindexDefaultACL(DK_ICM_ITEMTYPE_REINDEX_DEFAULT_ACL_TYPE)`, `setReindexACLControlModeType(DK_ICM_ITEMTYPE_REINDEX_ACL_CONTROL_MODE_TYPE)`, `setHoldContainer(boolean)`, `setItemsCanBeOnHold(boolean)`, `setDeleteExpiredItemsMaximumDuration(int)`, `setDeleteExpiredItemsMaximumRows(int)`, `setDeleteExpiredItemsCommitCount(int)`, `setDeleteExpiredItemsScheduleInformation(String)`, `setDeleteExpiredItemsSchedulerType(DK_ICM_ITEMTYPE_DELETE_EXPIRED_ITEMS_SCHEDULER_TYPE)`

**`add*` / `del*` / `delete*` / `remove*` / `update*`:** `addTextIndex(dkTextIndexICM)`, `removeTextIndex(DKTextIndexTypeICM)`, `addAutoLinkRule(DKAutoLinkDefICM)`, `addAutoLinkRule(dkCollection)`, `addItemTypeView(DKItemTypeViewDefICM)`, `removeItemTypeView(String)`, `deleteAutoLinkRule(DKAutoLinkDefICM)`, `deleteAutoLinkRule(dkCollection)`, `updateAutoLinkRule(DKAutoLinkDefICM)`, `updateAutoLinkRule(dkCollection)`, `addItemTypeRelation(DKItemTypeRelationDefICM)`, `removeItemTypeRelation(String)`, `updateItemTypeRelation(DKItemTypeRelationDefICM)`, `add(DKItemTypeRelationDefICM)`, `add(dkCollection)`, `del(DKItemTypeRelationDefICM)`, `del(dkCollection)`, `update(DKItemTypeRelationDefICM)`, `update(dkCollection)`

### 10.3 `DKDatastoreICM` — object/data mutations
- `addObject(dkDataObject)`, `addObject(dkDataObject,int)`, `addObject(dkDataObject,DKNVPair[])`
- `addObjects(dkCollection)`, `addObjects(dkCollection,int)`, `addObjects(dkCollection,DKNVPair[])`
- `updateObject(dkDataObject)`, `updateObject(dkDataObject,int)`
- `updateObjects(dkCollection)`, `updateObjects(dkCollection,int)`
- `deleteObject(dkDataObject)`, `deleteObject(dkDataObject,int)`
- `deleteObjects(dkCollection)`, `deleteObjects(dkCollection,int)`
- `moveObject(dkDataObject,String)`, `moveObject(dkDataObject,dkDataObject,int)`
- `commit()`, `rollback()`, `startTransaction()`
- `checkIn(dkDataObject)`, `checkOut(dkDataObject)`
- `changePassword(String,String,String)`
- `destroy()`, `disconnect()`
- `setOption(int,Object)`, `setTraceLevel(short)`
- `writeEvent(DKEventDefICM)`
- `addExtension(String,dkExtension)`, `removeExtension(String)`
- `registerMapping(DKNVPair)`, `unRegisterMapping(String)`
- `addPIDtoRecordIDMappings(dkCollection)`, `deletePIDtoRecordIDMappings(dkCollection)`
- `setSSLContextSMS(SSLContext)`, `setSSLSocketFactorySMS(SSLSocketFactory)`, `setSSLURLConnectionOpenedOnceAtLeast(boolean)`
- `turnOffPool()`, `returnConnectionToPool()`
- `clearCache()`, `clearCache(int)`

### 10.4 `DKDatastoreDefICM` — schema mutations
- `add*`: `add(dkEntityDef)`, `add(dkAttrDef)`, `add(dkAttrGroupDef)`, `add(DKLinkTypeDefICM)`, `add(DKSemanticTypeDefICM)`, `add(DKXDOClassificationDefICM)`, `add(DKComponentTypeIndexDefICM)`, `add(DKItemTypeRelationDefICM)`, `add(dkCollection)`, `add(DKMimeTypeDefICM)`, `addComponentTypeIndex(DKComponentTypeIndexDefICM)`, `addToACLCache(String,int)`
- `del*` / `delete*` / `remove*`: `del(dkEntityDef)`, `del(dkAttrDef)`, `del(dkAttrGroupDef)`, `del(DKLinkTypeDefICM)`, `del(DKSemanticTypeDefICM)`, `del(DKXDOClassificationDefICM)`, `del(DKComponentTypeIndexDefICM)`, `del(DKItemTypeRelationDefICM)`, `del(dkCollection)`, `del(DKMimeTypeDefICM)`, `removeComponentTypeIndex(DKComponentTypeIndexDefICM)`
- `update*`: `update(dkEntityDef)`, `update(dkAttrDef)`, `update(dkAttrGroupDef)`, `update(DKLinkTypeDefICM)`, `update(DKSemanticTypeDefICM)`, `update(DKXDOClassificationDefICM)`, `update(DKItemTypeRelationDefICM)`, `update(dkCollection)`, `update(DKMimeTypeDefICM)`, `update(DKMimeTypeDefICM,String)`, `updateTextIndexes(int)`, `updateTextIndexes(int,DKTextIndexTypeICM)`
- `rebuild*` / `recreate*` / `reorg*`: `rebuildComponentType(String)`, `rebuildComponentType(int)`, `rebuildAllComponentTypes()`, `recreateTextIndexes(String)`, `recreateTextIndexes(int)`, `reorgTextIndexes(int)`
- `make*`: `makeViewActive(DKItemTypeViewDefICM)`, `makeViewsActive(dkCollection)`
- `clear*`: `clearCache()`, `clearMimeTypeCache()`
- `set*`: `setDatastore(dkDatastore)`, `setDescription(DKNLSKeywordDefICM)`
- *(protected, still mutating)*: `add/del/update(DKPrivilegeICM)`, `add/del/update(DKPrivilegeGroupICM)`, `clearPrivilegeCache()`, `clearPrivilegeGroupCache()`

### 10.5 `DKDatastoreAdminICM`
- `addNLSLanguage(String,String)`, `addNLSLanguage(String,String,String)`
- `updateNLSLanguage(String,String)`, `deleteNLSLanguage(String)`
- `addNLSKeywordDesc(String,short,long,String)`, `updateNLSKeywordDesc(String,short,long,String)`, `deleteNLSKeywordDesc(String,short,long)`
- `addClientExit(DKClientExitDefICM)`, `updateClientExit(DKClientExitDefICM)`, `delClientExit(DKClientExitDefICM)`
- `setDatastore(dkDatastore)`, `clearCache()`
- `quiesce(int,int,int)`, `activate(int)` — server-level lifecycle changes (quiesce/activate the datastore)

### 10.6 `DKDatastoreDefICM` / `DKDatastoreICM` inherited plain `addObject`-family on `dkDatastore`
If the guard inspects declared methods of `dkDatastore` (superinterface), the full mutating set is: `addObject`×3, `addObjects`×3, `deleteObject`×3, `deleteObjects`×3, `updateObject`×3, `updateObjects`×3, `add`/`del` on entities, `addExtension`, `removeExtension`, `registerMapping`, `unRegisterMapping`, `startTransaction`, `commit`, `rollback`, `changePassword`, `destroy`, `clearCache`, `clearCache(int)`, `setOption`.

**Suggested guard regex** (server-mutating verb prefix):
`^(add|create|update|del|delete|assign|unassign|remove|commit|rollback|backfill|migrate|set|quiesce|activate|rebuild|recreate|reorg|make|register|unRegister|checkIn|checkOut|move|writeEvent|turnOff|returnConnection|changePassword)`  — **note** `create*` is only server-mutating when its result is passed to `add*`; `createEntity()`, `createItemType()`, `createDDO(...)`, `createAttr()`, `createAttrGroup()`, `createSubEntity()` build local objects only.

---

## 11. Supporting types needed so the chain compiles

| Type | FQCN | Role |
|---|---|---|
| `dkCollection` | `com.ibm.mm.sdk.common.dkCollection` | Return type of all `list*` methods. |
| `dkEntityDef` | `com.ibm.mm.sdk.common.dkEntityDef` (**interface**) | Return type of `retrieveEntity(...)`, `createEntity()`. Cast to `DKItemTypeDefICM`. |
| `dkDatastore` | `com.ibm.mm.sdk.common.dkDatastore` (**interface**) | Ctor param of `DKDatastoreDefICM`, `DKDatastoreAdminICM`, `DKPolicyMgmtICM`, `DKItemTypeDefICM`. |
| `dkDatastoreDef` | `com.ibm.mm.sdk.common.dkDatastoreDef` (**interface**) | Return type of `datastoreDef()`; declares `listEntities(int)`, `datastoreAdmin()`, `retrieveEntity(String)`. |
| `dkDatastoreAdmin` | `com.ibm.mm.sdk.common.dkDatastoreAdmin` (**interface**) | Return type of `datastoreAdmin()`. **No `policyMgmt()`.** |
| `DKNVPair` | `com.ibm.mm.sdk.common.DKNVPair` | Options arrays. |
| `DKAuthenticationData` | `com.ibm.mm.sdk.common.DKAuthenticationData` | `connectWithCredential`. |
| `DKLogonFailure` | `com.ibm.mm.sdk.common.DKLogonFailure` | Thrown by 5-arg `connectWithCredential`. |
| `DKHandle` | `com.ibm.mm.sdk.common.DKHandle` | `connection()`, `handle(String)`. |
| `DKDDO` | `com.ibm.mm.sdk.common.DKDDO` | `createDDO(...)`, object ops. |
| `dkDataObject` | `com.ibm.mm.sdk.common.dkDataObject` | Param type of `addObject`/`updateObject`/`deleteObject`. |
| `dkResultSetCursor` | `com.ibm.mm.sdk.common.dkResultSetCursor` | `execute(...)`. |
| `DKProjectionListICM` | `com.ibm.mm.sdk.common.DKProjectionListICM` | `DKRetrieveOptionsICM.attributeFilters(...)`. |
| `dkDatastoreIntICM` | `com.ibm.mm.sdk.common.dkDatastoreIntICM` | Impl interface of `DKDatastoreICM`; param of `DKRetrieveOptionsICM.createInstance(...)`. |
| `DKMessageIdICM` | `com.ibm.mm.sdk.common.DKMessageIdICM` | Implemented by `DKRetentionPolicyDefICM`. |
| `DKItemTypeRelationDefICM` | `com.ibm.mm.sdk.common.DKItemTypeRelationDefICM` | Item-type relation APIs. |
| `DKXDOClassificationDefICM` | `com.ibm.mm.sdk.common.DKXDOClassificationDefICM` | XDO classification APIs. |
| `DKAttrDefICM` / `DKAttrGroupDefICM` | `...DKAttrDefICM` / `...DKAttrGroupDefICM` | Attribute APIs. |
| `DKComponentTypeDefICM` | `com.ibm.mm.sdk.common.DKComponentTypeDefICM` | Direct superclass of `DKItemTypeDefICM`; declares `getId()`→`short`, `getIntId()`→`int`, `getDescription()`. |
| `dkAbstractEntityDef` | `com.ibm.mm.sdk.common.dkAbstractEntityDef` | Declares `getName()`, `getDescription()`, `getId()`→`short`, `getType()`→`short`, `getParentEntityName()`. |
| `dkAbstractDatastore` | `com.ibm.mm.sdk.server.dkAbstractDatastore` | Direct superclass of `DKDatastoreICM`. |
| `dkAbstractDatastoreDef` / `dkAbstractDatastoreAdmin` | `com.ibm.mm.sdk.common.dkAbstractDatastoreDef` / `...dkAbstractDatastoreAdmin` | Superclasses of the two def/admin ICM classes. |
| `DKLogException` / `DKLogMessageInserts` | `com.ibm.mm.sdk.logtool.DKLogException` / `...DKLogMessageInserts` | **Required** for `DKException` to compile. |

`DKRetrieveOptionsICM` (full public API, for completeness of the returned-object chain):
```
public static com.ibm.mm.sdk.common.DKRetrieveOptionsICM createInstance(com.ibm.mm.sdk.common.dkDatastoreIntICM) throws com.ibm.mm.sdk.common.DKUsageError, java.lang.Exception;
public com.ibm.mm.sdk.common.DKNVPair[] dkNVPair();
public void attributeFilters(com.ibm.mm.sdk.common.DKProjectionListICM);
public com.ibm.mm.sdk.common.DKProjectionListICM attributeFilters();
public void baseAttributes(boolean);           public boolean baseAttributes();
public void basePropertyAclName(boolean);      public boolean basePropertyAclName();
public void basePropertyCheckedOutDetails(boolean); public boolean basePropertyCheckedOutDetails();
public void childListOneLevel(boolean);        public boolean childListOneLevel();
public void childListAllLevels(boolean);       public boolean childListAllLevels();
public void childAttributes(boolean);          public boolean childAttributes();
public void linksOutbound(boolean);            public boolean linksOutbound();
public void linksInbound(boolean);             public boolean linksInbound();
public void linksInboundFolderSources(boolean); public boolean linksInboundFolderSources();
public void linksDescriptors(boolean);         public boolean linksDescriptors();
public void linksTypeFilter(java.lang.String) throws com.ibm.mm.sdk.common.DKUsageError, java.lang.Exception;
public java.lang.String linksTypeFilter();
public void linksLevelTwo(boolean);            public boolean linksLevelTwo();
public void linksLevelTwoCount(boolean);       public boolean linksLevelTwoCount();
public void partsList(boolean);                public boolean partsList();
public void partsAttributes(boolean);          public boolean partsAttributes();
public void partsPropertyAclName(boolean);     public boolean partsPropertyAclName();
public void resourceContent(boolean);          public boolean resourceContent();
public void behaviorIgnoreNoneFoundError(boolean); public boolean behaviorIgnoreNoneFoundError();
public void behaviorRecordLastRetrieveOption(boolean); public boolean behaviorRecordLastRetrieveOption();
public void behaviorSkipExistenceCheck(boolean); public boolean behaviorSkipExistenceCheck();
public void behaviorSkipResourceAttrRefresh(boolean); public boolean behaviorSkipResourceAttrRefresh();
public void behaviorPartsAttributesCoreOnly(boolean); public boolean behaviorPartsAttributesCoreOnly();
public void functionCheckOut(boolean);         public boolean functionCheckOut();
public void behaviorDoNotForceCheckout(boolean); public boolean behaviorDoNotForceCheckout();
public void behaviorRetrieveOnCheckoutError(boolean); public boolean behaviorRetrieveOnCheckoutError();
public void functionVersionLatest(boolean);    public boolean functionVersionLatest();
public java.lang.String toString();
public java.lang.String toString(boolean);
```
(Accessor pairs are setter-`void`/getter-`boolean` with identical names — Java overloading by return type is impossible, so they differ by arity: the setter takes `boolean`, the getter takes none. The exact read-only getter names above are the ones to mirror in a stub.)

---

## 12. Uncertainties I could not resolve from bytecode

1. **Parameter *names* and semantics.** `javap` gives types only. `connect(String,String,String,String)` is documented by IBM as `(datastoreName, userName, password, options)` but I **cannot confirm the order from the class file**. Verify against IBM CM 8.7 `DKDatastoreICM.connect` Javadoc before writing adapter code or a stub that asserts argument order.
2. **`DKDatastoreICM.connect(...)` static factory.** Many IBM sample programs use a static/`DKFactoryICM`-style creation path. This JAR exposes **no** static `connect` on `DKDatastoreICM`; only the two constructors. There is a `com.ibm.mm.sdk.common.DKFactoryICM` class in the JAR that I did **not** analyse — if the adapter intends to use it, that class needs its own javap pass.
3. **Enum ordinal/index mapping to the CM numeric codes.** The enums exist, but the bytecode's `<clinit>` does not reveal (and I did not decompile) which ordinal maps to which ICM integer. If the adapter must translate `DK_ICM_RETENTION_TYPE` ↔ a numeric CM code, that mapping must come from IBM documentation, not from this report.
4. **`DK_ICM_RETENTION_TYPE` "retention type values" as numbers.** Because it is an enum, there are **no** `static final int` retention-type constants. If the request's §3 expectation ("retention type values") assumed integers, that expectation is wrong for 8.7 — the enum is authoritative.
5. **`DKRetrieveOptionsICM.behaviorSkipResourceAttrRefresh`** — RESOLVED during this pass: `javap` confirms `public void behaviorSkipResourceAttrRefresh(boolean)` and `public boolean behaviorSkipResourceAttrRefresh()`. Both lines are verified; no residual doubt.
6. **Other `DKDatastoreICM` overloads I deliberately did not exhaustively enumerate.** `DKDatastoreICM` has 536 javap lines including many `createDDO`, SSL, pool, and perf members. I extracted every member the request named plus the ones needed for the chain. If the adapter needs e.g. `prefetchObjects`, `getRootFolder`, `getDefaultFolder`, `listEvents`, or the SSL getters, take those directly from the dump rather than assuming their signatures.
7. **Private/package-private members are excluded from the guard list.** §10 lists only members reachable/overridable through the public surface. `DKDatastoreDefICM` also has `protected` mutating `add/del/update(DKPrivilegeICM)` and `(DKPrivilegeGroupICM)`, and package-private `listPrivInGroup`. A source guard that only scans `public` declarations would miss those; a guard that scans all declarations should include them.
8. **`DKSystemException`** — I state definitively that it does not exist in this JAR. If the parent's specification insists on that name, it is referencing a different IBM product/version, and the stub must use `DKSystemError` instead (or the parent must confirm the intended class).
