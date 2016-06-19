#include <stdlib.h>
#include <stdio.h>
#include <string.h>

typedef struct {
    int tag;
    int size;
} Object;

typedef struct {
    long count;
    int *offsets;
} FieldLayout;

typedef struct {
    int size;
    int tag;
    FieldLayout fields;
} Class;

typedef struct {
    Object object;
    Class *elementType;
} Array;

typedef struct StackFrameStruct {
    int size;
    int reserved;
    struct StackFrameStruct *next;
} StackFrame;

typedef struct {
    int size;
    Object **data;
} StackRoots;

typedef struct TraversalStackStruct {
    int location;
    Object* data[4096];
    struct TraversalStackStruct* next;
} TraversalStack;

extern StackFrame *teavm_getStackTop();
extern StackRoots* teavm_getStackRoots();
extern Class* teavm_Array;
extern Class* teavm_booleanArray;
extern Class* teavm_byteArray;
extern Class* teavm_shortArray;
extern Class* teavm_charArray;
extern Class* teavm_intArray;
extern Class* teavm_longArray;
extern Class* teavm_floatArray;
extern Class* teavm_doubleArray;

static void* pool = NULL;
static int objectCount = 0;
static int objectsCapacity = 1024;
static int EMPTY_TAG = 0;
static int END_TAG = -1;
static int GC_MARK = 1 << 31;
static Object** objectsStart = NULL;
static Object** objects = NULL;
static TraversalStack* traversalStack;

static void* getPool() {
    if (pool == NULL) {
        int poolSize = 1024 * 1024 * 16;
        pool = malloc(poolSize);

        Object *root = (Object *) pool;
        root->tag = EMPTY_TAG;
        root->size = poolSize - sizeof(Object);

        char* address = (char *)pool;
        Object *terminator = (Object *) (address + root->size);
        terminator->tag = END_TAG;
        terminator->size = 0;

        objectsStart = malloc(sizeof(Object *) * objectsCapacity);
        objects = &objectsStart[0];
        objects[0] = root;
    }
    return pool;
}

static int objectSize(Object *object) {
    if (object->tag == EMPTY_TAG) {
        return object->size;
    } else {
        char *tagAddress = (char *) (long) (object->tag << 3);
        Class *cls = (Class *) tagAddress;
        if (cls == teavm_Array) {
            Array *array = (Array *) object;
            unsigned char *depthPtr = (unsigned char *) (array + 1);
            int depth = *depthPtr;
            int elementCount = array->object.size;
            if (depth == 0) {
                Class *elementType = array->elementType;
                if (elementType == teavm_booleanArray || elementType == teavm_byteArray) {
                    return elementCount * sizeof(char);
                } else if (elementType == teavm_shortArray || elementType == teavm_charArray) {
                    return elementCount * sizeof(short);
                } else if (elementType == teavm_intArray) {
                    return elementCount * sizeof(int);
                } else if (elementType == teavm_longArray) {
                    return elementCount * sizeof(long);
                } else if (elementType == teavm_floatArray) {
                    return elementCount * sizeof(float);
                } else if (elementType == teavm_floatArray) {
                    return elementCount * sizeof(double);
                }
            }
            return elementCount * sizeof(Object *);
        } else {
            return cls->size;
        }
    }
}

static void clearMarks() {
    Object *object = getPool();
    while (object->tag != END_TAG) {
        object->tag = object->tag & (-1 ^ GC_MARK);
        int size = objectSize(object);
        char *address = (char *) object + size;
        object = (Object *) address;
    }
}

static void pushObject(Object* object) {
    if (traversalStack->location >= 4096) {
        TraversalStack* next = malloc(sizeof(TraversalStack));
        next->next = traversalStack;
        traversalStack = next;
    }
    traversalStack->data[traversalStack->location++] = object;
}

static Object *popObject() {
    traversalStack->location--;
    if (traversalStack->location < 0) {
        if (traversalStack->next == NULL) {
            return NULL;
        }
        TraversalStack *next = traversalStack->next;
        free(traversalStack);
        traversalStack = next;
        traversalStack->location--;
    }
    return traversalStack->data[traversalStack->location];
}

static void markObject(Object *object) {
    if (object == NULL) {
        return;
    }

    pushObject(object);
    while (1) {
        object = popObject();
        if (object == NULL) {
            break;
        }
        if ((object->tag & GC_MARK) != 0) {
            break;
        }
        object->tag = object->tag | GC_MARK;

        char *address = (char *) object;
        Class *cls = (Class *) (long) (object->tag << 3);
        int fieldCount = (int) cls->fields.count;
        int *offsets = &cls->fields.offsets[0];
        for (int i = 0; i < fieldCount; ++i) {
            Object **fieldRef = (Object **) (address + offsets[i]);
            Object *field = *fieldRef;
            if (field != NULL && (field->tag & GC_MARK) != 0) {
                markObject(field);
            }
        }
    }
}

static void mark() {
    traversalStack = malloc(sizeof(TraversalStack));
    traversalStack->next = NULL;
    traversalStack->location = 0;

    StackRoots *roots = teavm_getStackRoots();
    for (int i = 0; i < roots->size; ++i) {
        markObject(roots->data[i]);
    }

    StackFrame *stack = teavm_getStackTop();
    while (stack != NULL) {
        Object **frameData = (Object **) (stack + 1);
        for (int i = 0; i < stack->size; ++i) {
            markObject(frameData[i]);
        }
        stack = stack->next;
    }

    free(traversalStack);
}

static int compareFreeChunks(const void* first, const void *second) {
    Object **a = (Object **) first;
    Object **b = (Object **) second;
    return (*b)->size - (*a)->size;
}

static void sweep() {
    objects = objectsStart;
    objectCount = 0;
    Object *object = getPool();
    Object *lastFreeSpace = NULL;
    int freeSize = 0;
    while (object->tag != END_TAG) {
        int size = objectSize(object);
        int free = 0;
        if (object->tag == 0) {
            free = 1;
        } else {
            free = (object->tag & GC_MARK) == 0;
            object->tag = object->tag & (-1 ^ GC_MARK);
        }
        if (free) {
            if (lastFreeSpace == NULL) {
                lastFreeSpace = object;
                freeSize = size;
            } else {
                freeSize += size;
            }
        } else {
            if (lastFreeSpace != NULL) {
                lastFreeSpace->size = freeSize;
                objects[objectCount] = lastFreeSpace;
                lastFreeSpace = NULL;
            }
        }
        char *address = (char *) object + size;
        object = (Object *) address;
    }

    if (lastFreeSpace != NULL) {
        lastFreeSpace->size = freeSize;
        objects[objectCount] = lastFreeSpace;
    }

    qsort(objects, objectCount, sizeof(Object *), &compareFreeChunks);
}

static int collectGarbage() {
    clearMarks();
    mark();
    sweep();
    return 1;
}

static Object* findAvailableChunk(int size) {
    while (objectCount > 0) {
        Object* chunk = *objects;
        if (chunk->size - 8 >= size || chunk->size == size) {
            return chunk;
        }
        --objectCount;
        ++objects;
    }
    return NULL;
}

static Object* getAvailableChunk(int size) {
    Object* chunk = findAvailableChunk(size);
    if (chunk != NULL) {
        return chunk;
    }
    if (!collectGarbage()) {
        printf("Out of memory\n");
        exit(2);
    }
    chunk = findAvailableChunk(size);
    if (chunk == NULL) {
        printf("Out of memory\n");
        exit(2);
    }
    return chunk;
}

Object *teavm_alloc(int tag) {
    Class* cls = (Class *) ((long) tag << 3);
    int size = cls->size;
    Object* chunk = getAvailableChunk(size);
    if (chunk->size > size) {
        Object* next = (Object *) ((char *) chunk + size);
        next->size = chunk->size - size;
        next->tag = EMPTY_TAG;
    }
    memset(chunk, 0, size);
    chunk->tag = tag;
    return chunk;
}

static Array *teavm_arrayAlloc(Class* cls, unsigned char depth, int arraySize, int elemSize) {
    int size = sizeof(Array) + elemSize * (arraySize + 1);
    Object* chunk = getAvailableChunk(size);
    if (chunk->size > size) {
        Object* next = (Object *) ((char *) chunk + size);
        next->size = chunk->size - size;
        next->tag = 0;
    }

    memset(chunk, 0, size);
    Array* array = (Array *) chunk;
    array->object.tag = (int) teavm_Array >> 3;
    array->object.size = arraySize;
    array->elementType = cls;
    unsigned char* depthPtr = (unsigned char *) (array + 1);
    depthPtr[0] = depth;

    return array;
}

Array *teavm_objectArrayAlloc(int tag, unsigned char depth, int size) {
    Class *cls = (Class *) (long) (tag << 3);
    return teavm_arrayAlloc(cls, depth, size, sizeof(Object *));
}
Array *teavm_booleanArrayAlloc(int size) {
    return teavm_arrayAlloc(teavm_booleanArray, 0, size, sizeof(char));
}
Array *teavm_byteArrayAlloc(int size) {
    return teavm_arrayAlloc(teavm_byteArray, 0, size, sizeof(char));
}
Array *teavm_shortArrayAlloc(int size) {
    return teavm_arrayAlloc(teavm_shortArray, 0, size, sizeof(short));
}
Array *teavm_charArrayAlloc(int size) {
    return teavm_arrayAlloc(teavm_charArray, 0, size, sizeof(short));
}
Array *teavm_intArrayAlloc(int size) {
    return teavm_arrayAlloc(teavm_intArray, 0, size, sizeof(int));
}
Array *teavm_longArrayAlloc(int size) {
    return teavm_arrayAlloc(teavm_longArray, 0, size, sizeof(long));
}
Array *teavm_floatArrayAlloc(int size) {
    return teavm_arrayAlloc(teavm_floatArray, 0, size, sizeof(float));
}
Array *teavm_doubleArrayAlloc(int size) {
    return teavm_arrayAlloc(teavm_doubleArray, 0, size, sizeof(double));
}