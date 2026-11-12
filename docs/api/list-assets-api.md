`POST https://ark.ap-southeast-1.byteplusapi.com/?Action=ListAssets&Version=2024-01-01`

This document describes the request and response parameters of the List Assets API. You can retrieve Assets that match specified filters.

<div data-tips="true" data-tips-type="danger" data-tips-is-title="true">Pagination methods</div>


<div data-tips="true" data-tips-type="danger">This API supports the following pagination methods:</div>



* <div data-tips="true" data-tips-type="danger"><strong>Method 1: NextToken pagination (recommended)</strong>: Use <em>MaxResults</em> to set the maximum number of records returned per page, and use <em>NextToken</em> as the pagination token to retrieve the next page.</div>


   * <div data-tips="true" data-tips-type="danger">For the first request, you can set <em>MaxResults</em> without specifying <em>NextToken</em>. If another page is available, the response includes <em>NextToken</em>.</div>


   * <div data-tips="true" data-tips-type="danger">For subsequent requests, pass the <em>NextToken</em> returned by the preceding request. If <em>NextToken</em> is not returned, all records have been retrieved.</div>


   * <div data-tips="true" data-tips-type="danger">We recommend keeping <em>Filter</em>, <em>ProjectName</em>, <em>SortBy</em>, and <em>SortOrder</em> consistent with the first request.</div>


   * <div data-tips="true" data-tips-type="danger">This method does not return <em>TotalCount</em>, <em>PageNumber</em>, or <em>PageSize</em>.</div>


* <div data-tips="true" data-tips-type="danger"><strong>Method 2: Page\-number pagination (compatible)</strong>: Use <em>PageSize</em> to set the number of records returned per page and <em>PageNumber</em> to specify the page.</div>


   * <div data-tips="true" data-tips-type="danger">The response includes <em>TotalCount</em>, <em>PageNumber</em>, and <em>PageSize</em>.</div>



<div data-tips="true" data-tips-type="danger"><strong>Notes</strong>:</div>



* <div data-tips="true" data-tips-type="danger">The two pagination methods are mutually exclusive. Do not pass parameters from both methods in the same request.</div>


* <div data-tips="true" data-tips-type="danger">If no pagination parameters are specified, Method 1 is used and <em>MaxResults</em> defaults to <code>10</code>. Use the returned <em>NextToken</em> to retrieve subsequent pages.</div>


* <div data-tips="true" data-tips-type="danger">When the query range is large, that is, when <code>PageSize * PageNumber > 20000</code>, use <code>NextToken</code> for deep pagination.</div>




<Tabs>
<Tab zoneid="B1wt09EUhI" title="Quick Links">
<TabTitle>Quick Links</TabTitle>

<span>![图片](https://portal.volccdn.com/obj/volcfe/cloud-universal-doc/upload_57d0bca8e0d122ab1191b40101b5df75.png) </span> [Tutorial](https://ai.byteplus.com/ark/region:ap-southeast-1/docs/ModelArk/2333565) <span>![图片](https://portal.volccdn.com/obj/volcfe/cloud-universal-doc/upload_f45b5cd5863d1eed3bc3c81b9af54407.png) </span> [API List](https://ai.byteplus.com/ark/region:ap-southeast-1/docs/ModelArk/2333565#577c8ba0) <span>![图片](https://portal.volccdn.com/obj/volcfe/cloud-universal-doc/upload_bef4bc3de3535ee19d0c5d6c37b0ffdd.png) </span> [Enable Model](https://ai.byteplus.com/ark/region:ap-southeast-1/openManagement?LLM=%7B%7D&OpenTokenDrawer=false)


</Tab>
<Tab zoneid="dmqk9zbW18" title="Authentication">
<TabTitle>Authentication</TabTitle>

This API only supports Access Key (AK/SK) authentication.


</Tab>
</Tabs>



---



<span id="cBcCiB1q"></span>
## Request parameters

> Go to [Response parameters](https://ai.byteplus.com/ark/region:ap-southeast-1/docs/ModelArk/2318273#7hRth93G)



---



<span id="7f8hIDhA"></span>
### Request body


---



**Filter** `object` `Required`

Filter conditions.


Attributes


---



Filter.**GroupIds** `string[]`

A list of Asset Group IDs that the Assets belong to.


---



Filter.**GroupType** `string` `Required`

The Asset Group type. Valid values:


* `AIGC`: Digital characters

* `LivenessFace`: Real\-person portrait



---



Filter.**Statuses** `string[]`

Asset status. Valid values:


* `Active`: Pre\-processing completed and can be used

* `Processing`: Pre\-processing in progress and cannot be used

* `Failed`: Pre\-processing failed



---



Filter.**Name** `string`

The Asset name, up to 64 characters.



---



**NextToken** `string`

The pagination token for NextToken pagination. Set it to the `NextToken` returned by the preceding call to this API.

<div data-tips="true" data-tips-type="tip" data-tips-is-title="true">Usage</div>



* <div data-tips="true" data-tips-type="tip">Do not specify <code>NextToken</code> in the first request.</div>


* <div data-tips="true" data-tips-type="tip">To retrieve the next page, pass the <code>NextToken</code> returned by the preceding response.</div>



<div data-tips="true" data-tips-type="warning" data-tips-is-title="true">Restrictions</div>



* <div data-tips="true" data-tips-type="warning">Do not specify <code>PageNumber</code> or <code>PageSize</code> together with this parameter.</div>


* <div data-tips="true" data-tips-type="warning">An empty, modified, or cross\-API, cross\-account, or cross\-project token is invalid.</div>




---



**MaxResults** `integer (i32)`

The maximum number of records returned per page when using NextToken pagination.

<div data-tips="true" data-tips-type="tip" data-tips-is-title="true">Usage</div>



* <div data-tips="true" data-tips-type="tip">You can use this parameter to set the maximum number of records returned for the first request.</div>


* <div data-tips="true" data-tips-type="tip">If no pagination parameters are specified, NextToken pagination is used and <code>MaxResults</code> defaults to <code>10</code>.</div>


* <div data-tips="true" data-tips-type="tip">For subsequent requests that include <code>NextToken</code>, you can omit this parameter. Its default value is <code>10</code>.</div>



<div data-tips="true" data-tips-type="warning" data-tips-is-title="true">Restrictions</div>


<div data-tips="true" data-tips-type="warning">Do not specify <code>PageNumber</code> or <code>PageSize</code> together with this parameter.</div>


Value range: `[1, 100]`


---



**SortBy** `string`

The field used for sorting. Default is `CreateTime`. Valid values:


* `CreateTime`: Sort by creation time

* `UpdateTime`: Sort by update time

* `GroupId`: Sort by Asset Group ID



---



**SortOrder** `string`

The sorting order. Default is `Desc`. Valid values:


* `Desc`: Descending

* `Asc`: Ascending



---



**ProjectName** `string`

The project name that the resource belongs to. Default is `default`. If the resource is not in the default project, set the correct project name.


---



**PageNumber** `integer (i64)`

The page number for page\-number pagination, starting from `1`. When `PageSize * PageNumber > 20000`, use `NextToken` for deep pagination.

<div data-tips="true" data-tips-type="warning" data-tips-is-title="true">Restrictions</div>



* <div data-tips="true" data-tips-type="warning"><code>PageNumber</code> and <code>PageSize</code> must be used together.</div>


* <div data-tips="true" data-tips-type="warning">Do not specify <code>MaxResults</code> or <code>NextToken</code> together with this parameter.</div>




---



**PageSize** `integer (i64)`

The number of records returned per page when using page\-number pagination. When `PageSize * PageNumber > 20000`, use `NextToken` for deep pagination.

<div data-tips="true" data-tips-type="warning" data-tips-is-title="true">Restrictions</div>



* <div data-tips="true" data-tips-type="warning"><code>PageNumber</code> and <code>PageSize</code> must be used together.</div>


* <div data-tips="true" data-tips-type="warning">Do not specify <code>MaxResults</code> or <code>NextToken</code> together with this parameter.</div>



Value range: `[1, 100]`

<span id="7hRth93G"></span>
## Response parameters

> Go to [Request parameters](https://ai.byteplus.com/ark/region:ap-southeast-1/docs/ModelArk/2318273#cBcCiB1q)



---



**Items** `object[]`

An array of matching Assets.


Attributes


---



Items.**Id** `string`

The Asset ID.


---



Items.**Name** `string`

The Asset name, up to 64 characters.


---



Items.**URL** `string`

The public URL of the Asset. Valid for 12 hours. Save it in time.


---



Items.**GroupId** `string`

The Asset Group ID that the Asset belongs to.


---



Items.**AssetType** `string`

The Asset type. Valid values:


* `Image`

* `Video`

* `Audio`



---



Items.**Status** `string`

The Asset status. Valid values:


* `Active`

* `Processing`

* `Failed`



---



Items.**Error** `object`

Error information. Returned when `Status` is `Failed`.


Attributes


---



Items.Error.**Code** `string`

Error code.


---



Items.Error.**Message** `string`

Error message.



---



Items.**Moderation** `object`

Content Pre\-filter review related information.


Attributes


---



Items.Moderation.**Strategy** `string`

Indicates whether the Content Pre\-filter review is turned on for this asset. Available values:


* `Default`: Content Pre\-filter review is on.

* `Skip`: Most non\-baseline content security review policies are off.



---



Items.**ProjectName** `string`

The project name that the resource belongs to.


---



Items.**CreateTime** `string`

Creation time.


---



Items.**UpdateTime** `string`

Update time.


---



Items.**LastInferenceTime** `string`

The most recent time when this asset was submitted to a video generation task. The value is in RFC 3339 format, accurate to seconds without fractional seconds, for example, `2026-07-14T02:08:49Z` or `2026-07-14T10:08:49+08:00`.

This time is the task submission time, not the task processing time. After a task is submitted successfully, `LastInferenceTime` is updated regardless of whether the subsequent execution succeeds or fails.

For newly submitted tasks, when you query related assets by using `GetAsset` or `ListAssets`, the update of this field may be delayed by 1–2 minutes. If the asset has not been invoked by any task after 2026\-07\-24, this field is not returned.



---



**TotalCount** `integer (i64)`

The total number of matching assets. Returned only when page\-number pagination is used.


---



**PageNumber** `integer (i64)`

The current page number. Returned only when page\-number pagination is used.


---



**PageSize** `integer (i64)`

The number of records returned per page. Returned only when page\-number pagination is used.

Value range: `[1, 100]`


---



**NextToken** `string`

The pagination token for the next page. Returned only when NextToken pagination is used and another page is available.

Pass this value unchanged in the subsequent request. If this field is not returned, all records have been retrieved.


---



<span id="UXjzfxmT"></span>
## Request example

```text
POST /?Action=ListAssets&Version=2024-01-01 HTTP/1.1
Host: ark.ap-southeast-1.byteplusapi.com
Content-Type: application/json
X-Date: 20260328T000000Z
X-Content-Sha256: 287e874e******d653b44d21e
Authorization: HMAC-SHA256 Credential=AKLTYz******/20260328/ap-southeast-1/ark/request, SignedHeaders=content-type;host;x-content-sha256;x-date, Signature=47a7d934******e41085f

{
  "Filter": {
    "GroupIds": [
      "group-2026**********-*****"
    ],
    "GroupType": "AIGC",
    "Statuses": [
      "Active"
    ]
  },
  "MaxResults": 1,
  "SortBy": "CreateTime",
  "SortOrder": "Desc"
}
```


<span id="HBCxkDKx"></span>
## Response example

```JSON
{
  "ResponseMetadata": {
    "RequestId": "20260328000000000000000000000000",
    "Action": "ListAssets",
    "Version": "2024-01-01",
    "Service": "ark",
    "Region": "ap-southeast-1"
  },
  "Result": {
    "Items": [
      {
        "Id": "Asset-2026**********-*****",
        "Name": "test",
        "URL": "https://example.com/asset-url",
        "GroupId": "group-2026**********-*****",
        "AssetType": "Image",
        "Status": "Active",
        "Moderation": {
          "Strategy": "Default"
         },
        "ProjectName": "default",
        "CreateTime": "2026-03-28T00:00:00Z",
        "UpdateTime": "2026-03-28T00:00:00Z",
        "LastInferenceTime": "2026-07-24T02:08:49Z"
      }
    ],
    "NextToken": "****bmNyeXB0ZWRfY3Vyc29yIjoiLi4****"
  }
}
```




