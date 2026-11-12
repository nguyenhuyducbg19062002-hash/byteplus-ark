`POST https://ark.ap-southeast-1.byteplusapi.com/?Action=ListAssetGroups&Version=2024-01-01`

Query the list of asset groups that meet the filter conditions.

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


* <div data-tips="true" data-tips-type="danger">When the query range is large, that is, when <code>PageSize * PageNumber > 10000</code>, use <code>NextToken</code> for deep pagination.</div>




<Tabs>
<Tab zoneid="xHaLuSKscq" title="Quick Links">
<TabTitle>Quick Links</TabTitle>

<span>![图片](https://portal.volccdn.com/obj/volcfe/cloud-universal-doc/upload_57d0bca8e0d122ab1191b40101b5df75.png) </span> [Tutorial](https://ai.byteplus.com/ark/region:ap-southeast-1/docs/ModelArk/2333565) <span>![图片](https://portal.volccdn.com/obj/volcfe/cloud-universal-doc/upload_f45b5cd5863d1eed3bc3c81b9af54407.png) </span> [API List](https://docs.byteplus.com/en/docs/ModelArk/) <span>![图片](https://portal.volccdn.com/obj/volcfe/cloud-universal-doc/upload_bef4bc3de3535ee19d0c5d6c37b0ffdd.png) </span> [Enable Model](https://ai.byteplus.com/ark/region:ap-southeast-1/openManagement?LLM=%7B%7D&OpenTokenDrawer=false)


</Tab>
<Tab zoneid="bBycUpzQL4" title="Authentication">
<TabTitle>Authentication</TabTitle>

This API only supports Access Key (AK/SK) authentication.


</Tab>
</Tabs>



---



<span id="0Hf4mQGP"></span>
## Request parameters

<span id="lAkCFS0L"></span>
### Request body


---



**Filter** `object` `Required`

Filter conditions for search.


Attributes

Filter.**GroupIds** `array`

The Id of the asset group to which the asset belongs.


---



Filter.**GroupType** `string` `Required`

Asset Group type:


* `AIGC`: Digital characters.

* `LivenessFace`: Real\-person portrait



---



Filter.**Name** `string`

The name of the asset group, up to 64 characters.



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

The page number for page\-number pagination, starting from `1`. When `PageSize * PageNumber > 10000`, use `NextToken` for deep pagination.

<div data-tips="true" data-tips-type="warning" data-tips-is-title="true">Restrictions</div>



* <div data-tips="true" data-tips-type="warning"><code>PageNumber</code> and <code>PageSize</code> must be used together.</div>


* <div data-tips="true" data-tips-type="warning">Do not specify <code>MaxResults</code> or <code>NextToken</code> together with this parameter.</div>




---



**PageSize** `integer (i64)`

The number of records returned per page when using page\-number pagination. When `PageSize * PageNumber > 10000`, use `NextToken` for deep pagination.

<div data-tips="true" data-tips-type="warning" data-tips-is-title="true">Restrictions</div>



* <div data-tips="true" data-tips-type="warning"><code>PageNumber</code> and <code>PageSize</code> must be used together.</div>


* <div data-tips="true" data-tips-type="warning">Do not specify <code>MaxResults</code> or <code>NextToken</code> together with this parameter.</div>



Value range: `[1, 100]`

<span id="QCBDgXij"></span>
## Response parameters


---



**TotalCount** `int (i64)`

The total number of matching Asset Groups. Returned only when page\-number pagination is used.


---



**Items** `object[]`

An array of asset groups that meet the filter conditions.


Attributes


---



Items.**Id** `string`

The Id of the asset group.


---



Items.**Name** `string`

The name of the asset group, up to 64 characters.


---



Items.**Description** `string`

The description of the asset group, up to 300 characters.


---



Items.**GroupType** `string`

The type of the asset group.


* `AIGC`: Digital characters

* `LivenessFace`: Real\-person portrait



---



Items.**ProjectName** `string`

The name of the project to which the resource belongs.


---



Items.**CreateTime** `string`

Created time.


---



Items.**UpdateTime** `string`

Updated time.



---



**PageNumber** `int (i64)`

The current page number. Returned only when page\-number pagination is used.


---



**PageSize** `int (i64)`

The number of records returned per page. Returned only when page\-number pagination is used.

Value range: `[1, 100]`


---



**NextToken** `string`

The pagination token for the next page. Returned only when NextToken pagination is used and another page is available.

Pass this value unchanged in the subsequent request. If this field is not returned, all records have been retrieved.


---



<span id="saOjG5Md"></span>
## Request example

```text
POST /?Action=ListAssetGroups&Version=2024-01-01 HTTP/1.1
Host: ark.ap-southeast-1.byteplusapi.com
Content-Type: application/json
X-Date: 20260328T000000Z
X-Content-Sha256: 287e874e******d653b44d21e
Authorization: HMAC-SHA256 Credential=AKLTYz******/20260328/ap-southeast-1/ark/request, SignedHeaders=content-type;host;x-content-sha256;x-date, Signature=47a7d934******e41085f

{
  "Filter": {
    "GroupIds": [
      "group-2026**********-****1",
      "group-2026**********-****2"
    ],
    "GroupType": "AIGC"
  },
  "MaxResults": 1,
  "SortBy": "CreateTime",
  "SortOrder": "Desc"
}
```


<span id="qoRws9Sg"></span>
## Response example

```JSON
{
  "ResponseMetadata": {
    "RequestId": "20260328000000000000000000000000",
    "Action": "ListAssetGroups",
    "Version": "2024-01-01",
    "Service": "ark",
    "Region": "ap-southeast-1"
  },
  "Result": {
    "Items": [
      {
        "Id": "group-2026**********-****1",
        "Name": "test",
        "Description": "test",
        "GroupType": "AIGC",
        "ProjectName": "default",
        "CreateTime": "2026-03-28T00:00:00Z",
        "UpdateTime": "2026-03-28T00:00:00Z"
      }
    ],
    "NextToken": "****bmNyeXB0ZWRfY3Vyc29yIjoiLi4****"
  }
}
```




