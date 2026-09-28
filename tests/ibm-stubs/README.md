# tests/ibm-stubs - test-only compile stubs for the IBM Content Manager 8.7 SDK

These files are **signatures only**. They are the minimum `com.ibm.*` surface that lets
`src/ibm/java` (and `src/ibm-test/java`) typecheck and compile in CI on a machine with **zero
proprietary JARs**. They are never packaged, never shipped, never on the production class path and
never a runtime dependency.

**A compile against a real `cmbicmsdk81.jar` is authoritative over everything in this directory.**
If a stub and the real JAR disagree, the JAR wins and the stub is wrong.

## Where the signatures come from

* `harness/GOAL_02_IMPLEMENTATION_SPEC.md` section 1 (the frozen interface surface) and section 3
  (how the build uses these files).
* `IBM_CM87_SDK_API_SURFACE.md` (842-line reconnaissance, generated from real `javap` output against
  `cmbicmsdk81.jar`, class-file major version 52).
* Direct `javap -public -constants` runs against the extracted 8.7 classes, performed while writing
  each file. **Nothing here was guessed and no numeric value was invented.**

The six corrections that break the build if ignored are all encoded here:

| Fact | Where it shows |
| --- | --- |
| `DKDatastoreICM` is `com.ibm.mm.sdk.**server**.DKDatastoreICM` | `server/DKDatastoreICM.java`; it is the only class needed from that package |
| `DKSystemException` does not exist, the class is `DKSystemError` | `common/DKSystemError.java`; the other name appears nowhere |
| the SDK has **no `close()`** anywhere | no `close` member exists in any stub (see the negative check below) |
| `datastoreDef()` does **not** narrow its return type | `dkDatastoreDef datastoreDef()`; the cast to `DKDatastoreDefICM` is structurally required |
| `datastoreAdmin()` does **not** narrow either, and `dkDatastoreAdmin` has no `policyMgmt()` | `dkDatastoreAdmin` is declared empty; only `DKDatastoreAdminICM.policyMgmt()` can reach retention |
| retention type / time unit / expiration action are nested **enums**, not ints | `DKRetentionPolicyDefICM$DK_ICM_RETENTION_TYPE`, `$DK_ICM_POLICY_TIME_UNIT`, `$DK_ICM_EXPIRATION_ACTION_TYPE` |

Also encoded: `DKItemTypeDefICM.getIntId()` returns `int` (the inherited `getId()` is a lossy
`short`; `getEntityId()`/`getItemTypeId()` do not exist), and `DKDatastoreICM.isConnected()` declares
no `throws` clause while `dkDatastore.isConnected()` declares `throws java.lang.Exception`.

## The enum caveat - do not guess a numeric code

`DKRetentionPolicyDefICM$DK_ICM_RETENTION_TYPE`, `$DK_ICM_POLICY_TIME_UNIT` and
`$DK_ICM_EXPIRATION_ACTION_TYPE` are real Java enums, so `name()`, `values()` and `valueOf(String)`
work. Their **mapping to the underlying CM numeric codes is not recoverable from the class files**
(reconnaissance report section 12 item 3), and neither is any ordinal-to-code relationship.

Therefore: no numeric value is pinned anywhere in this directory, no enum carries a code field, and
the adapter must map only what is certain and render everything else as `UNKNOWN(<value>)` - never a
guessed number, never blank (`GOAL_02_IMPLEMENTATION_SPEC.md` sections 1 and 6.1). The same warning
is repeated in the Javadoc of all three enums and in `EXPECTED_SIGNATURES.txt`.

## Deliberate omissions - the read-only surface is structural, not just a regex

There is **no `set*` member anywhere in this stub set**, and no server-mutating member: no `commit`,
`rollback`, `startTransaction`, no server write (`add`/`addObject(s)`/`addProjection`-family writes/
`del`/`delete*`/`remove*`/`checkIn`/`checkOut`/`changePassword`/`quiesce`/`activate`/`makeViewActive`/
`reorg`/`rebuild`/`recreate` receiver calls), no `clearCache` (client-side, but still cache
invalidation) and no `close`.

The only writers that survive are two purely local builders whose state never reaches the server:
`DKProjectionListICM.addProjection(...)` (builds a projection list) and `DKLogMessageInserts.insert` /
`addFirst` / `clear` (builds a message-insert buffer).

Consequence, and it is intentional: a write path added to `src/ibm/java` **does not compile** against
these stubs. The `ReadOnlySourceGuardTest` regex guard stays the second line of defence, not the
only one. `.tools/t1-chain-probe/NegativeProbe.java` (temporary, outside the repository) proves it:
`datastore.close()`, `datastore.commit()`, `policies.add(...)`, `policies.update(...)`,
`policies.del(...)`, `policy.setName(...)` and `itemType.setIntId(...)` all fail with
`cannot find symbol`.

Members that exist in the real SDK but are deliberately **not** declared, with the reason:

| Not declared | Why |
| --- | --- |
| `DKDatastoreICM.connection()` and everything returning `DKHandle` | native JDBC extraction is forbidden in this project |
| `DKException.setErrorId(int)` | the only mutator on the SDK exception type; the adapter reads `errorCode()` and never writes an id back |
| `DKNVPair.setName/setValue/set` | this stub set declares no `set*` member at all; the constructor carries the name and value |
| `connectWithCredential(...)`, `DKAuthenticationData`, `DKLogonFailure` | the proven call is `connect(ssid, user, password, "")`; credential-object login is not used |
| `DKDatastoreICM.addObject(s)`, `updateObject(s)`, `deleteObject(s)`, `moveObject`, `commit`, `rollback`, `startTransaction`, `checkIn`, `checkOut`, `changePassword`, `setOption`, `setTraceLevel`, `writeEvent`, the SSL setters, `turnOffPool`, `returnConnectionToPool`, the `createDDO` family | mutating, or irrelevant to a read-only metadata path |
| `DKDatastoreICM.listDataSources()`, `listDataSourceNames()`, `userName()`, `datastoreName()`, `datastoreType()`, `getSessionId*`, `getLogID()`, `schemaName()`, `languageCode()`, `clearCache*`, `getAPIVersion()`, `getLSVersion()` | not needed by the read chain; `getAPIVer()` / `getAPILSVer()` (static, the CM API release) **are** declared |
| `DKDatastoreDefICM.createEntity()`, `createItemType()`, `listEntities(DKNVPair[])`, `retrieveItemTypeView(...)`, `listItemTypesWithAutoLinkEnabled()`, `listItemTypeRelations()`, `clearCache()` | mutating or out of scope. `listEntities(DKNVPair[])` is the reason nothing else consumes `DKNVPair` |
| `DKDatastoreAdminICM` beyond `policyMgmt()`: `authorizationMgmt`, `userManagement`, `configurationManagement`, `adminDomainsMgmt`, `mimeTypeMgmt`, `quiesceStatus`, `getConnectionUserID`, `setDatastore`, `clearCache`, `quiesce`/`activate` | not read-path; `getConnectionUserID()` also invites putting an identity into diagnostics |
| `DKPolicyMgmtICM.add/update/del/clearCache` | mutating; `add` would additionally drag in `DKAlreadyExistException` |
| `DKItemTypeDefICM` beyond the read getters: the ~45 `set*` members, the text-index/auto-link/relation/view families, `getItemTypeFlag()`, `getPartIDByName/NameByID`, `getItemTypeACLCode()/Name()`, `listItemTypeViews()`, and the `DK_ICM_ITEMTYPE_REINDEX_*` / `_DELETE_EXPIRED_ITEMS_SCHEDULER_TYPE` enums | not needed by `ItemTypeInfo`; adding the scheduler/acl getters later is a one-line change plus `check-signatures.sh --write` |
| `DKRetentionPolicyDefICM` setters (`setID`, `setName`, `setRetentionType`, ... ) | mutating |
| `dkDatastore` object data / query / mapping / extension families, and the `dkQueryManager` -> `dkQueryEvaluator` superinterface chain | the Goal 02 read path issues no query and writes no object |
| `dkEntityDef`/`dkAbstractEntityDef` sub-entity, attribute and `clearCache` families | not needed |
| `dkCollection` mutation (`addElement`, `removeElementAt`, ...) and `setOwner`/`getOwner`/`setName` | collection writes; `setOwner`/`getOwner` would drag in `dkDataObjectBase` for no benefit |
| `dkDatastoreIntICM` members (checkIn/checkOut/moveObject/SSL/feature probes) | declared empty on purpose; nothing read-only goes through it |
| `DKMessageId` / `DKMessageIdICM` message-id constants | hundreds of ints, none read by the adapter; the types are kept because other stubs implement them |
| `DKRetrieveOptionsICM` link/parts/check-out/version options and `toString(boolean)` | not read-path; the check-out options would also put `checkOut` in the stub surface |
| the remainder of `DKConstant`, `DKConstantICM` (hundreds of constants) | only the values the read mapping needs are pinned (all verified with `javap -constants`) |

Retention getters *are* complete for the Goal 02 contract: `getName`, `getID`, `getDescription`,
`getRetentionType`, `isRetentionEnabled`, `getRetentionTimePeriod`, `getDefaultRetentionTimeUnit`,
`isExpirationEnabled`, `getExpirationTimePeriod`, `getDefaultExpirationTimeUnit`,
`getExpirationAction`, `getDeleteExpiredItemsScheduleInformation`, `getDeleteExpiredItemsCommitCount`,
`getDeleteExpiredItemsMaximumRows`, `getDeleteExpiredItemsMaximumDuration`,
`isDeleteExpiredItemsForceCheckInEnabled`.

## Body policy: no behaviour

Every non-constructor body is exactly
`throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");`.
Constructors only delegate to a super constructor (or are empty), so the exception types can still be
instantiated as objects of the right shape. Nothing implements behaviour, keeps state, or performs
I/O.

Runtime consequence, please plan for it: an IBM test that runs with the stub set instead of a real
JAR cannot drive real SDK objects - any `listEntities(...)`, `isConnected()` or policy getter throws
`UnsupportedOperationException`. IBM suites that need real CM behaviour must run only when
`lib/ibm/*.jar` is present, or must exercise the adapter through fakes for the vendor-neutral types.
Two things do work with the stubs alone, because they need no SDK call: the enum constant names
(`DK_ICM_RETENTION_TYPE.FIXED_TIME.name()` and friends) and all `DKConstantICM` /
`DKDatastoreDefICM` / `DKItemTypeDefICM` constants, which are compile-time inlined.

`private static final long serialVersionUID = 1L;` is present on the `Serializable` stubs only to
silence `-Xlint:serial`. It is private and invisible to `javap -public`.

## How the build uses this directory

```sh
# 1. compile the stubs (must not be a packaged directory)
javac --release 17 -encoding UTF-8 -Xlint:all -d .tools/ibm-stub-classes $(find tests/ibm-stubs -name '*.java')

# 2. compile the IBM source set against src/main/java + the stub classes
javac --release 17 -encoding UTF-8 -Xlint:all -cp build/classes:.tools/ibm-stub-classes -d build/ibm-classes $(find src/ibm/java -name '*.java')

# 3. keep the stubs honest
tests/ibm-stubs/check-signatures.sh --classes .tools/ibm-stub-classes
```

The stub classes directory must never be on the packaging file set: `build.sh` packages
`build/classes` (+ `build/ibm-classes`), so a scratch directory such as `.tools/ibm-stub-classes`
(git-ignored) or a dedicated build subdirectory is required. A `com/ibm/mm/sdk/**` entry inside
`build/ibm-classes` would mean the stubs leaked into the shipped jar.

## `EXPECTED_SIGNATURES.txt` and `check-signatures.sh`

`EXPECTED_SIGNATURES.txt` is the concatenated `javap -public -constants` output of every class built
from this directory, in `LC_ALL=C` order of the fully qualified name, behind a `#`-prefixed header.
It pins the class declarations (including `extends`/`implements`), every public member, every throws
clause, and every constant **value** - so `-constants` is required, not optional.

```sh
tests/ibm-stubs/check-signatures.sh            # compile + compare (exit 0 = match)
tests/ibm-stubs/check-signatures.sh --write    # regenerate after a deliberate stub change
tests/ibm-stubs/check-signatures.sh --classes DIR   # check a classes dir the build already made
```

The comparison ignores `#` header lines and normalises CRLF to LF; everything else must match byte
for byte. `--write` is the only supported way to change the file - never hand-edit it.

Known surface trims that a reviewer will see in that file, all deliberate and documented above:
`dkDatastoreAdmin`, `dkDatastoreIntICM`, `DKMessageId` and `DKMessageIdICM` are empty; `dkDatastore`
does not extend `dkQueryManager`; `dkAbstractDatastoreDef` declares no member.
