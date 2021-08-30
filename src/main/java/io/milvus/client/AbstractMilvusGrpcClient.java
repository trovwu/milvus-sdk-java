package io.milvus.client;

import com.google.protobuf.ByteString;
import io.grpc.StatusRuntimeException;
import io.milvus.exception.ParamException;
import io.milvus.grpc.*;
import io.milvus.param.DeleteParam;
import io.milvus.param.InsertParam;
import io.milvus.param.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;

public abstract class AbstractMilvusGrpcClient implements MilvusClient {

    private static final Logger logger = LoggerFactory.getLogger(AbstractMilvusGrpcClient.class);

    protected abstract MilvusServiceGrpc.MilvusServiceBlockingStub blockingStub();

    protected abstract MilvusServiceGrpc.MilvusServiceFutureStub futureStub();

    @Override
    public R<Boolean> hasCollection(String collectionName) {
        HasCollectionRequest hasCollectionRequest = HasCollectionRequest.newBuilder()
                .setCollectionName(collectionName)
                .build();

        BoolResponse response;
        try {
            response = blockingStub().hasCollection(hasCollectionRequest);
        } catch (StatusRuntimeException e) {
            logger.error("[milvus] hasCollection:{} request error: {}", collectionName, e.getMessage());
            return R.failed(e);
        }
        Boolean aBoolean = Optional.ofNullable(response)
                .map(BoolResponse::getValue)
                .orElse(false);

        return R.success(aBoolean);
    }

    @Override
    public R<FlushResponse> flush(String collectionName, String dbName) {
        return flush(Collections.singletonList(collectionName), dbName);
    }

    @Override
    public R<FlushResponse> flush(String collectionName) {
        return flush(Collections.singletonList(collectionName), "");

    }

    @Override
    public R<FlushResponse> flush(List<String> collectionNames) {
        return flush(collectionNames, "");

    }

    @Override
    public R<FlushResponse> flush(List<String> collectionNames, String dbName) {
        MsgBase msgBase = MsgBase.newBuilder().setMsgType(MsgType.Flush).build();
        FlushRequest.Builder builder = FlushRequest.newBuilder().setBase(msgBase).setDbName(dbName);
        collectionNames.forEach(builder::addCollectionNames);
        FlushRequest flushRequest = builder.build();
        FlushResponse flush = null;
        try {
            flush = blockingStub().flush(flushRequest);
        } catch (Exception e) {
            return R.failed(e);
        }
        return R.success(flush);
    }

    @Override
    public R<MutationResult> delete(DeleteParam deleteParam) {

        DeleteRequest deleteRequest = DeleteRequest.newBuilder()
                .setBase(MsgBase.newBuilder().setMsgType(MsgType.Delete).build())
                .setCollectionName(deleteParam.getCollectionName())
                .setPartitionName(deleteParam.getPartitionName())
                .build();

        MutationResult delete = null;
        try {
            delete = blockingStub().delete(deleteRequest);
        } catch (Exception e) {
            return R.failed(e);
        }
        return R.success(delete);
    }

    public R<MutationResult> insert(InsertParam insertParam) {
        //check params : two steps
        // 1、check sdk incoming params
        String collectionName = insertParam.getCollectionName();
        String partitionName = insertParam.getPartitionName();
        int fieldNum = insertParam.getFieldNum();
        List<String> fieldNames = insertParam.getFieldNames();
        List<DataType> dataTypes = insertParam.getDataTypes();
        List<List<?>> fieldValues = insertParam.getFieldValues();
        Integer filedNameSize = Optional.ofNullable(fieldNames).map(List::size).orElse(0);
        Integer dTypeSize = Optional.ofNullable(dataTypes).map(List::size).orElse(0);
        int fieldValueSize = Optional.ofNullable(fieldValues).map(List::size).orElse(0);
        if (fieldNum != fieldValueSize || fieldNum != filedNameSize || fieldNum != dTypeSize) {
            throw new ParamException("size is illegal");
        }

        //2、need to DDL request to get collection meta schema; whether the collection schema needs to be cached locally todo
        assert fieldValues != null;
        int numRow = fieldValues.get(0).size();

        //3、gen insert request
        MsgBase msgBase = MsgBase.newBuilder().setMsgType(MsgType.Insert).build();
        InsertRequest.Builder insertBuilder = InsertRequest.newBuilder()
                .setCollectionName(collectionName)
                .setPartitionName(partitionName)
                .setBase(msgBase)
                .setNumRows(numRow);

        // gen fieldData
        for (int i = 0; i < fieldNum; i++) {
            insertBuilder.addFieldsData(genFieldData(fieldNames.get(i), dataTypes.get(i), fieldValues.get(i)));
        }

        InsertRequest insertRequest = insertBuilder.build();
        MutationResult insert = null;
        try {
            insert = blockingStub().insert(insertRequest);
        } catch (Exception e) {
            R.failed(e);
        }
        return R.success(insert);

    }

    private FieldData genFieldData(String fieldName, DataType dataType, List<?> objects) {
        if (objects == null) {
            throw new ParamException("params is null");
        }
        FieldData.Builder builder = FieldData.newBuilder();
        if (vectorDataType.contains(dataType)) {
            if (dataType == DataType.FloatVector) {
                List<Float> floats = new ArrayList<>();
                // 每个object是个list
                for (Object object : objects) {
                    if (object instanceof List) {
                        List list = (List) object;
                        list.forEach(o -> floats.add((Float) o));
                    } else {
                        throw new ParamException("参数有问题");
                    }
                }
                int dim = floats.size() / objects.size();
                FloatArray floatArray = FloatArray.newBuilder().addAllData(floats).build();
                VectorField vectorField = VectorField.newBuilder().setDim(dim).setFloatVector(floatArray).build();
                return builder.setFieldName(fieldName).setType(DataType.FloatVector).setVectors(vectorField).build();
            } else if (dataType == DataType.BinaryVector) {
                List<ByteBuffer> bytes = objects.stream().map(p -> (ByteBuffer) p).collect(Collectors.toList());
                ;
                ByteString byteString = ByteString.copyFrom((ByteBuffer) bytes);
                int dim = objects.size();
                VectorField vectorField = VectorField.newBuilder().setDim(dim).setBinaryVector(byteString).build();
                return builder.setFieldName(fieldName).setType(DataType.BinaryVector).setVectors(vectorField).build();
            }


        } else {
            switch (dataType) {
                case None:
                case UNRECOGNIZED:
                    throw new ParamException("not support this dataType:" + dataType);
                case Int64:
                case Int32:
                case Int16:
                    List<Long> longs = objects.stream().map(p -> (Long) p).collect(Collectors.toList());
                    LongArray longArray = LongArray.newBuilder().addAllData(longs).build();
                    ScalarField scalarField1 = ScalarField.newBuilder().setLongData(longArray).build();
                    return builder.setFieldName(fieldName).setType(dataType).setScalars(scalarField1).build();
                case Int8:
                    List<Integer> integers = objects.stream().map(p -> (Integer) p).collect(Collectors.toList());
                    IntArray intArray = IntArray.newBuilder().addAllData(integers).build();
                    ScalarField scalarField2 = ScalarField.newBuilder().setIntData(intArray).build();
                    return builder.setFieldName(fieldName).setType(dataType).setScalars(scalarField2).build();
                case Bool:
                    List<Boolean> booleans = objects.stream().map(p -> (Boolean) p).collect(Collectors.toList());
                    BoolArray boolArray = BoolArray.newBuilder().addAllData(booleans).build();
                    ScalarField scalarField3 = ScalarField.newBuilder().setBoolData(boolArray).build();
                    return builder.setFieldName(fieldName).setType(dataType).setScalars(scalarField3).build();
                case Float:
                    List<Float> floats = objects.stream().map(p -> (Float) p).collect(Collectors.toList());
                    FloatArray floatArray = FloatArray.newBuilder().addAllData(floats).build();
                    ScalarField scalarField4 = ScalarField.newBuilder().setFloatData(floatArray).build();
                    return builder.setFieldName(fieldName).setType(dataType).setScalars(scalarField4).build();
                case Double:
                    List<Double> doubles = objects.stream().map(p -> (Double) p).collect(Collectors.toList());
                    DoubleArray doubleArray = DoubleArray.newBuilder().addAllData(doubles).build();
                    ScalarField scalarField5 = ScalarField.newBuilder().setDoubleData(doubleArray).build();
                    return builder.setFieldName(fieldName).setType(dataType).setScalars(scalarField5).build();
                case String:
                    List<String> strings = objects.stream().map(p -> (String) p).collect(Collectors.toList());
                    StringArray stringArray = StringArray.newBuilder().addAllData(strings).build();
                    ScalarField scalarField6 = ScalarField.newBuilder().setStringData(stringArray).build();
                    return builder.setFieldName(fieldName).setType(dataType).setScalars(scalarField6).build();
            }
        }
        return null;
    }

    private static final Set<DataType> vectorDataType = new HashSet<DataType>() {{
        add(DataType.FloatVector);
        add(DataType.BinaryVector);
    }};
}
