`POST https://ark.ap-southeast-1.byteplusapi.com/?Action=GetAsset&Version=2024-01-01`

Query the asset status and confirm whether pre\-processing is complete before using the asset for inference.


<Tabs>
<Tab zoneid="ZnZjkjNN2G" title="Quick Links">
<TabTitle>Quick Links</TabTitle>

<span>![图片](https://portal.volccdn.com/obj/volcfe/cloud-universal-doc/upload_57d0bca8e0d122ab1191b40101b5df75.png) </span> [Tutorial](https://ai.byteplus.com/ark/region:ap-southeast-1/docs/ModelArk/2333565) <span>![图片](https://portal.volccdn.com/obj/volcfe/cloud-universal-doc/upload_f45b5cd5863d1eed3bc3c81b9af54407.png) </span> [API List](https://ai.byteplus.com/ark/region:ap-southeast-1/docs/ModelArk/2333565#577c8ba0) <span>![图片](https://portal.volccdn.com/obj/volcfe/cloud-universal-doc/upload_bef4bc3de3535ee19d0c5d6c37b0ffdd.png) </span> [Enable Model](https://ai.byteplus.com/ark/region:ap-southeast-1/openManagement?LLM=%7B%7D&OpenTokenDrawer=false)


</Tab>
<Tab zoneid="b2olcOjmKC" title="Authentication">
<TabTitle>Authentication</TabTitle>

This API only supports Access Key (AK/SK) authentication.


</Tab>
</Tabs>



---



<span id="7tfSFdDJ"></span>
## Request parameters

> Go to [Response parameters](https://ai.byteplus.com/ark/region:ap-southeast-1/docs/ModelArk/2318274#2mAB9iCX)



---



<span id="PYNANfYl"></span>
### Request body


---



**Id** `string` `Required`

The Asset ID.


---



**ProjectName** `string`

The name of the project to which the asset belongs. The default value is `default`.

If the resource is not in the default project, enter the correct project name. For more information about projects, see the related [IAM docs](https://docs.byteplus.com/en/docs/byteplus-platform/docs-managing-projects).

<span id="2mAB9iCX"></span>
## Response parameters

> Go to [Request parameters](https://ai.byteplus.com/ark/region:ap-southeast-1/docs/ModelArk/2318274#7tfSFdDJ)



---



**Id** `string`

The Asset ID.


---



**Name** `string`

The Asset name, up to 64 characters.


---



**URL** `string`

The access URL of the Asset. Valid for 12 hours. Save it in time.


---



**AssetType** `string`

The Asset type. Valid values:


* `Image`

* `Video`

* `Audio`



---



**GroupId** `string`

The Asset Group ID that the Asset belongs to.


---



**Status** `string`

The Asset status. Valid values:


* `Active`: Pre\-processing completed and can be used

* `Processing`: Pre\-processing in progress and cannot be used

* `Failed`: Pre\-processing failed



---



**Error** `object`

Error information. Returned when `Status` is `Failed`.


Attributes


---



Error.**Code** `string`

Error code.


---



Error.**Message** `string`

Error message.



---



**Moderation** `object`

Content Pre\-filter review related information.


Attributes


---



Moderation.**Strategy** `string`

Indicates whether the Content Pre\-filter review is turned on for this asset. Available values:


* `Default`: Content Pre\-filter review is on.

* `Skip`: Most non\-baseline content security review policies are off.



---



**CreateTime** `string`

Creation time.


---



**UpdateTime** `string`

Update time.


---



**LastInferenceTime** `string`

The most recent time when this asset was submitted to a video generation task. The value is in RFC 3339 format, accurate to seconds without fractional seconds, for example, `2026-07-14T02:08:49Z` or `2026-07-14T10:08:49+08:00`.

This time is the task submission time, not the task processing time. After a task is submitted successfully, `LastInferenceTime` is updated regardless of whether the subsequent execution succeeds or fails.

For newly submitted tasks, when you query related assets by using `GetAsset` or `ListAssets`, the update of this field may be delayed by 1–2 minutes. If the asset has not been invoked by any task after 2026\-07\-24, this field is not returned.


---



**ProjectName** `string`

The project name that the resource belongs to.


---



<span id="3v1uJ0ZZ"></span>
## Request example

```text
POST /?Action=GetAsset&Version=2024-01-01 HTTP/1.1
Host: ark.ap-southeast-1.byteplusapi.com
Content-Type: application/json
X-Date: 20260328T000000Z
X-Content-Sha256: 287e874e******d653b44d21e
Authorization: HMAC-SHA256 Credential=AKLTYz******/20260328/ap-southeast-1/ark/request, SignedHeaders=content-type;host;x-content-sha256;x-date, Signature=47a7d934******e41085f

{
  "Id": "Asset-2026**********-*****"
}
```


<span id="ZOqJrx12"></span>
## Response example

```JSON
{
  "ResponseMetadata": {
    "RequestId": "20260328000000000000000000000000",
    "Action": "GetAsset",
    "Version": "2024-01-01",
    "Service": "ark",
    "Region": "ap-southeast-1"
  },
  "Result": {
    "Id": "Asset-2026**********-*****",
    "Name": "test",
    "URL": "https://example.com/asset-url",
    "AssetType": "Image",
    "GroupId": "group-2026**********-*****",
    "Status": "Active",
    "Moderation": {
      "Strategy": "Default"
    },
    "CreateTime": "2026-03-28T00:00:00Z",
    "UpdateTime": "2026-03-28T00:00:00Z",
    "LastInferenceTime": "2026-07-24T10:08:49+08:00",
    "ProjectName": "default"
  }
}
```




