$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '..\tools\usb-pair-debug.ps1')
function Assert($value,$message){if(-not $value){throw $message}}
function Reject([scriptblock]$Call){$rejected=$false;try{& $Call|Out-Null}catch{$rejected=$true};Assert $rejected 'unsafe UI data was accepted'}
$xml='<?xml version="1.0"?><hierarchy><node package="com.android.settings" text="123456"/><node package="com.android.settings" text="192.0.2.4:43071"/></hierarchy>'
$fields=Get-SuiLianPairFields (Get-SuiLianSettingsNodes $xml)
Assert ($fields.port -eq 43071 -and $fields.code.Length -eq 6) 'pair fields not recognized'
Reject {Get-SuiLianSettingsNodes ($xml.Replace('com.android.settings','other.app'))}
Reject {Get-SuiLianPairFields (Get-SuiLianSettingsNodes ($xml.Replace(':43071',':99999')))}
Reject {Get-SuiLianPairFields (Get-SuiLianSettingsNodes ($xml.Replace('</hierarchy>','<node package="com.android.settings" text="192.0.2.4:45678"/></hierarchy>')))}
Reject {Get-SuiLianPairFields (Get-SuiLianSettingsNodes ($xml.Replace('123456','not a code')))}
Write-Output 'PASS: own Settings only, single code/port, valid port range, ambiguous popup rejected. No device or secrets used.'
